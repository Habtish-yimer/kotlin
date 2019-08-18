/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.serialization

import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.descriptors.*
import org.jetbrains.kotlin.backend.common.serialization.*
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.impl.IrLoopBase
import org.jetbrains.kotlin.ir.symbols.IrExternalPackageFragmentSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.load.java.JavaVisibilities
import org.jetbrains.kotlin.load.kotlin.JvmPackagePartSource
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedMemberDescriptor
import org.jetbrains.kotlin.backend.common.serialization.proto.DescriptorReference as ProtoDescriptorReference
import org.jetbrains.kotlin.backend.common.serialization.proto.IrSymbol as ProtoSymbol
import org.jetbrains.kotlin.backend.common.serialization.proto.IrSymbolData as ProtoSymbolData
import org.jetbrains.kotlin.backend.common.serialization.proto.IrSymbolKind as ProtoSymbolKind
import org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeIndex as ProtoTypeIndex
import org.jetbrains.kotlin.backend.common.serialization.proto.String as ProtoString
import org.jetbrains.kotlin.backend.common.serialization.proto.Visibility as ProtoVisibility

class JvmIrDeserializer(
    val module: ModuleDescriptor,
    val logger: LoggingContext,
    val builtIns: IrBuiltIns,
    val symbolTable: SymbolTable,
    val languageVersionSettings: LanguageVersionSettings
) : IrDeserializer {

    val knownToplevelFqNames = mutableMapOf<Long, FqName>()

    private val deserializedSymbols = mutableMapOf<UniqIdKey, IrSymbol>()

    private val externalReferences = mutableMapOf<Long, JvmIr.JvmExternalPackage>()

    override fun getDeclaration(symbol: IrSymbol, backoff: (IrSymbol) -> IrDeclaration): IrDeclaration {
        if (symbol.isBound) return symbol.owner as IrDeclaration
        val descriptor =
            symbol.descriptor as? DeserializedMemberDescriptor ?: symbol.descriptor as? DeserializedClassDescriptor
            ?: return backoff(symbol)
        val moduleDescriptor = descriptor.module

        val toplevelDescriptor = descriptor.toToplevel()
        val packageFragment = symbolTable.findOrDeclareExternalPackageFragment(toplevelDescriptor.containingDeclaration as PackageFragmentDescriptor)

        if (toplevelDescriptor is ClassDescriptor) {
            val classHeader = (toplevelDescriptor.source as? KotlinJvmBinarySourceElement)?.binaryClass?.classHeader ?: return backoff(symbol)
            if (classHeader.serializedIr == null || classHeader.serializedIr!!.isEmpty()) return backoff(symbol)

            val irProto = JvmIr.JvmIrClass.parseFrom(classHeader.serializedIr)
            val moduleDeserializer = ModuleDeserializerWithReferenceLookup(moduleDescriptor, irProto.auxTables, backoff)
            consumeUniqIdTable(irProto.auxTables.uniqIdTable, moduleDeserializer)
            consumeExternalRefsTable(irProto.auxTables.externalRefs)
            moduleDeserializer.deserializeIrClass(irProto.irClass, parent = packageFragment)
            assert(symbol.isBound)
            return symbol.owner as IrDeclaration
        } else {
            val jvmPackagePartSource = (descriptor as DeserializedMemberDescriptor).containerSource as? JvmPackagePartSource ?: return backoff(symbol)
            val classHeader = jvmPackagePartSource.knownJvmBinaryClass?.classHeader ?: return backoff(symbol)
            if (classHeader.serializedIr == null || classHeader.serializedIr!!.isEmpty()) return backoff(symbol)

            val irProto = JvmIr.JvmIrFile.parseFrom(classHeader.serializedIr)

            val moduleDeserializer = ModuleDeserializerWithReferenceLookup(moduleDescriptor, irProto.auxTables, backoff)
            consumeUniqIdTable(irProto.auxTables.uniqIdTable, moduleDeserializer)
            consumeExternalRefsTable(irProto.auxTables.externalRefs)

            for (declaration in irProto.declarationContainer.declarationList) {
                val member = moduleDeserializer.deserializeDeclaration(declaration, parent = packageFragment)
                packageFragment.declarations.add(member)
            }
            assert(symbol.isBound)
            return symbol.owner as IrDeclaration
        }
    }

    private fun consumeUniqIdTable(table: JvmIr.UniqIdTable, moduleDeserializer: ModuleDeserializer) {
        for (entry in table.infosList) {
            val id = entry.id
            val toplevelFqName = FqName(moduleDeserializer.deserializeString(entry.toplevelFqName))
            val oldFqName = knownToplevelFqNames[id]
            assert(oldFqName == null || oldFqName == toplevelFqName) { "FqName table clash: $oldFqName vs $toplevelFqName" }
            knownToplevelFqNames[id] = toplevelFqName
        }
    }

    /* External references are deserialized lazily, as the last resource for when there is no descriptor available for a given symbol ref */
    private fun consumeExternalRefsTable(table: JvmIr.ExternalRefs) {
        for (reference in table.referencesList) {
            externalReferences[reference.id] = table.packagesList[reference.index]
        }
    }

    private tailrec fun DeclarationDescriptor.toToplevel(): DeclarationDescriptor =
        if (containingDeclaration is PackageFragmentDescriptor) this else containingDeclaration!!.toToplevel()

    override fun declareForwardDeclarations() {}

    abstract inner class ModuleDeserializer(
        val moduleDescriptor: ModuleDescriptor,
        val auxTables: JvmIr.AuxTables,
        val backoff: (IrSymbol) -> IrDeclaration
    ) :
        IrModuleDeserializer(logger, builtIns, symbolTable) {

        val uniqIdAware = JvmDescriptorUniqIdAware(symbolTable, backoff)

        val descriptorReferenceDeserializer = JvmDescriptorReferenceDeserializer(moduleDescriptor, uniqIdAware)

        private var moduleLoops = mutableMapOf<Int, IrLoopBase>()

        abstract protected fun referenceDeserializedSymbol(proto: ProtoSymbolData, descriptor: DeclarationDescriptor?): IrSymbol

        protected fun referenceDeserializedSymbolBare(
            proto: ProtoSymbolData,
            descriptor: DeclarationDescriptor?
        ) = when (proto.kind) {
            ProtoSymbolKind.ANONYMOUS_INIT_SYMBOL ->
                IrAnonymousInitializerSymbolImpl(
                    descriptor as ClassDescriptor?
                        ?: WrappedClassDescriptor()
                )
            ProtoSymbolKind.CLASS_SYMBOL ->
                symbolTable.referenceClass(
                    descriptor as ClassDescriptor?
                        ?: WrappedClassDescriptor()
                )
            ProtoSymbolKind.CONSTRUCTOR_SYMBOL ->
                symbolTable.referenceConstructor(
                    descriptor as ClassConstructorDescriptor?
                        ?: WrappedClassConstructorDescriptor()
                )
            ProtoSymbolKind.TYPE_PARAMETER_SYMBOL ->
                symbolTable.referenceTypeParameter(
                    descriptor as TypeParameterDescriptor?
                        ?: WrappedTypeParameterDescriptor()
                )
            ProtoSymbolKind.ENUM_ENTRY_SYMBOL ->
                symbolTable.referenceEnumEntry(
                    descriptor as ClassDescriptor?
                        ?: WrappedEnumEntryDescriptor()
                )
            ProtoSymbolKind.STANDALONE_FIELD_SYMBOL ->
                symbolTable.referenceField(WrappedFieldDescriptor())

            ProtoSymbolKind.FIELD_SYMBOL ->
                symbolTable.referenceField(
                    descriptor as PropertyDescriptor?
                        ?: WrappedPropertyDescriptor()
                )
            ProtoSymbolKind.FUNCTION_SYMBOL ->
                symbolTable.referenceSimpleFunction(
                    descriptor as FunctionDescriptor?
                        ?: WrappedSimpleFunctionDescriptor()
                )
            ProtoSymbolKind.VARIABLE_SYMBOL ->
                IrVariableSymbolImpl(
                    descriptor as VariableDescriptor?
                        ?: WrappedVariableDescriptor()
                )
            ProtoSymbolKind.VALUE_PARAMETER_SYMBOL ->
                IrValueParameterSymbolImpl(
                    descriptor as ParameterDescriptor?
                        ?: WrappedValueParameterDescriptor()
                )
            ProtoSymbolKind.RECEIVER_PARAMETER_SYMBOL ->
                IrValueParameterSymbolImpl(
                    descriptor as ParameterDescriptor? ?: WrappedReceiverParameterDescriptor()
                )
            ProtoSymbolKind.PROPERTY_SYMBOL ->
                symbolTable.referenceProperty(
                    descriptor as PropertyDescriptor? ?: WrappedPropertyDescriptor()
                )
            ProtoSymbolKind.TYPEALIAS_SYMBOL ->
                symbolTable.referenceTypeAlias(
                    descriptor as TypeAliasDescriptor? ?: WrappedTypeAliasDescriptor()
                )
            else -> TODO("Unexpected classifier symbol kind: ${proto.kind}")
        }

        override fun deserializeString(proto: ProtoString): String {
            return auxTables.stringTable.getStrings(proto.index)
        }

        override fun deserializeIrType(proto: ProtoTypeIndex): IrType {
            val typeData = auxTables.typeTable.getTypes(proto.index)
            return deserializeIrTypeData(typeData)
        }

        override fun deserializeIrSymbol(proto: ProtoSymbol): IrSymbol {
            val symbolData = auxTables.symbolTable.getSymbols(proto.index)
            return deserializeIrSymbolData(symbolData)
        }

        private fun deserializeIrSymbolData(proto: ProtoSymbolData): IrSymbol {
            val key = proto.uniqId.uniqIdKey(moduleDescriptor)
            return deserializedSymbols.getOrPut(key) {
                val descriptor = if (proto.hasDescriptorReference()) {
                    deserializeDescriptorReference(proto.descriptorReference)
                } else {
                    null
                }

                referenceDeserializedSymbol(proto, descriptor)
            }
        }

        override fun deserializeDescriptorReference(proto: ProtoDescriptorReference) =
            descriptorReferenceDeserializer.deserializeDescriptorReference(
                deserializeString(proto.packageFqName),
                deserializeString(proto.classFqName),
                deserializeString(proto.name),
                if (proto.hasUniqId()) proto.uniqId.index else null,
                isEnumEntry = proto.isEnumEntry,
                isEnumSpecial = proto.isEnumSpecial,
                isDefaultConstructor = proto.isDefaultConstructor,
                isFakeOverride = proto.isFakeOverride,
                isGetter = proto.isGetter,
                isSetter = proto.isSetter,
                isTypeParameter = proto.isTypeParameter
            )

        override fun deserializeLoopHeader(loopIndex: Int, loopBuilder: () -> IrLoopBase) =
            moduleLoops.getOrPut(loopIndex, loopBuilder)

        override fun deserializeVisibility(value: ProtoVisibility): Visibility = when (deserializeString(value.name)) {
            "package" -> JavaVisibilities.PACKAGE_VISIBILITY
            "protected_static" -> JavaVisibilities.PROTECTED_STATIC_VISIBILITY
            "protected_and_package" -> JavaVisibilities.PROTECTED_AND_PACKAGE
            else -> super.deserializeVisibility(value)
        }
    }

    inner class ModuleDeserializerWithoutReferenceLookup(
        moduleDescriptor: ModuleDescriptor,
        auxTables: JvmIr.AuxTables,
        backoff: (IrSymbol) -> IrDeclaration
    ) : ModuleDeserializer(moduleDescriptor, auxTables, backoff) {
        override fun referenceDeserializedSymbol(proto: ProtoSymbolData, descriptor: DeclarationDescriptor?): IrSymbol {
            return referenceDeserializedSymbolBare(proto, descriptor)
        }
    }
    inner class ModuleDeserializerWithReferenceLookup(
        moduleDescriptor: ModuleDescriptor,
        auxTables: JvmIr.AuxTables,
        backoff: (IrSymbol) -> IrDeclaration
    ) : ModuleDeserializer(moduleDescriptor, auxTables, backoff) {
        override fun referenceDeserializedSymbol(
            proto: ProtoSymbolData,
            descriptor: DeclarationDescriptor?
        ): IrSymbol = if (descriptor == null && !proto.uniqId.isLocal) {
            val uniqIdKey = UniqIdKey(null, proto.uniqId.uniqId())
            deserializedSymbols[uniqIdKey] ?: run {
                val externalPackageProto = externalReferences[proto.uniqId.index]
                    ?: error("External reference absent from external references table: ${deserializeString(proto.fqname)}")
                val packageFragment = IrExternalPackageFragmentImpl(
                    DescriptorlessExternalPackageFragmentSymbol(),
                    FqName(externalPackageProto.fqName)
                )
                for (memberProto in externalPackageProto.declarationContainer.declarationList) {
                    val toplevel = ModuleDeserializerWithoutReferenceLookup(moduleDescriptor, auxTables, backoff)
                        .deserializeDeclaration(memberProto, packageFragment)
                    packageFragment.declarations.add(toplevel)
                }
                deserializedSymbols[uniqIdKey] ?: error("Symbol unbound even after deserializing external reference")
            }
        } else {
            referenceDeserializedSymbolBare(proto, descriptor)
        }

    }
}

// Copied from MoveBodilessDeclarationToSeparatePlace.kt
private class DescriptorlessExternalPackageFragmentSymbol : IrExternalPackageFragmentSymbol {
    override val descriptor: PackageFragmentDescriptor
        get() = error("Operation is unsupported")

    private var _owner: IrExternalPackageFragment? = null
    override val owner get() = _owner!!

    override val isBound get() = _owner != null

    override fun bind(owner: IrExternalPackageFragment) {
        _owner = owner
    }
}