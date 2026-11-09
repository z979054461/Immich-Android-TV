#!/usr/bin/env python3
"""Structural release-Dex audit of apksig's reflection contract (not a VM verifier).

References/string constants alone are insufficient: these checks follow class_defs,
class_data and annotation directories. Supports ordinary little-endian DEX 035-040;
unknown/container formats fail closed. No third-party Python dependencies.
"""
import struct

PREFIX = 'Lcom/android/apksig/internal/'
ASN_CLASS = PREFIX + 'asn1/Asn1Class;'
ASN_FIELD = PREFIX + 'asn1/Asn1Field;'
SIGNATURE = 'Ldalvik/annotation/Signature;'
DEFAULT = 'Ldalvik/annotation/AnnotationDefault;'
RETENTION = 'Ljava/lang/annotation/Retention;'
MODELS = {
    'SignedData': ('version', 'digestAlgorithms', 'encapContentInfo', 'certificates', 'crls', 'signerInfos'),
    'ContentInfo': ('contentType', 'content'),
    'SignerInfo': ('version', 'sid', 'digestAlgorithm', 'signedAttrs', 'signatureAlgorithm', 'signature', 'unsignedAttrs'),
}
GENERICS = {
    ('SignedData', 'digestAlgorithms'): PREFIX + 'pkcs7/AlgorithmIdentifier;',
    ('SignedData', 'certificates'): PREFIX + 'asn1/Asn1OpaqueObject;',
    ('SignedData', 'crls'): 'Ljava/nio/ByteBuffer;',
    ('SignedData', 'signerInfos'): PREFIX + 'pkcs7/SignerInfo;',
    ('SignerInfo', 'unsignedAttrs'): PREFIX + 'pkcs7/Attribute;',
}


def require(ok, message):
    if not ok:
        raise ValueError('apksig DEX audit: ' + message)


class Dex:
    def __init__(self, data):
        self.data = data
        require(len(data) >= 112 and data[:4] == b'dex\n' and data[4:7] in
                (b'035', b'037', b'038', b'039', b'040') and data[7] == 0, 'unsupported/truncated header')
        require(self.u32(32) == len(data) and self.u32(36) == 112 and self.u32(40) == 0x12345678,
                'invalid file size, header size or endian tag')
        self.strings = [self.string(self.u32(o)) for o in self.table(56, 4)]
        self.types = [self.at(self.strings, self.u32(o)) for o in self.table(64, 4)]
        self.protos = []
        for o in self.table(72, 12):
            parameters = self.u32(o + 8)
            args = [] if parameters == 0 else [self.at(self.types, self.u16(i))
                    for i in self.region(parameters + 4, self.u32(parameters), 2)]
            self.protos.append((self.at(self.types, self.u32(o + 4)), args))
        self.fields = [(self.at(self.types, self.u16(o)), self.at(self.strings, self.u32(o + 4)))
                       for o in self.table(80, 8)]
        self.methods = [(self.at(self.types, self.u16(o)), self.at(self.strings, self.u32(o + 4)),
                         self.at(self.protos, self.u16(o + 2))) for o in self.table(88, 8)]
        self.classes = {}
        for o in self.table(96, 32):
            name = self.at(self.types, self.u32(o))
            require(name not in self.classes, 'duplicate class definition')
            flags = self.u32(o + 4)
            annotations, field_annotations = self.directory(self.u32(o + 20))
            fields, methods = self.class_data(self.u32(o + 24), name)
            require(set(field_annotations) <= set(fields), 'annotation for undefined field')
            self.classes[name] = (flags, fields, methods, annotations, field_annotations)

    @staticmethod
    def at(items, index):
        require(0 <= index < len(items), 'invalid index')
        return items[index]

    def region(self, offset, count, width):
        require(offset >= 0 and count >= 0 and offset + count * width <= len(self.data), 'out-of-bounds structure')
        return range(offset, offset + count * width, width)

    def u32(self, offset):
        self.region(offset, 1, 4)
        return struct.unpack_from('<I', self.data, offset)[0]

    def u16(self, offset):
        self.region(offset, 1, 2)
        return struct.unpack_from('<H', self.data, offset)[0]

    def table(self, header, width):
        count, offset = self.u32(header), self.u32(header + 4)
        require(not count or offset >= 112, 'invalid table offset')
        return self.region(offset, count, width)

    def leb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            self.region(offset, 1, 1)
            byte = self.data[offset]
            offset += 1
            if shift == 28:
                require(byte < 16, 'overflowing uleb128')
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError('apksig DEX audit: unterminated uleb128')

    def string(self, offset):
        _, start = self.leb(offset)
        end = self.data.find(b'\0', start)
        require(end >= start, 'unterminated string')
        try:
            return self.data[start:end].replace(b'\xc0\x80', b'\0').decode('utf-8', 'surrogatepass')
        except UnicodeError as error:
            raise ValueError('apksig DEX audit: invalid string') from error

    def annotation(self, offset, depth=0):
        require(depth < 32, 'nested annotation limit')
        type_id, offset = self.leb(offset)
        count, offset = self.leb(offset)
        self.region(offset, count, 1)
        values = {}
        for _ in range(count):
            name_id, offset = self.leb(offset)
            name = self.at(self.strings, name_id)
            require(name not in values, 'duplicate annotation element')
            value, offset = self.value(offset, depth + 1)
            values[name] = value
        return (self.at(self.types, type_id), values), offset

    def value(self, offset, depth):
        require(depth < 32, 'nested value limit')
        self.region(offset, 1, 1)
        tag = self.data[offset]
        kind, arg = tag & 31, tag >> 5
        offset += 1
        if kind == 0x1c:
            require(arg == 0, 'invalid array encoding')
            count, offset = self.leb(offset)
            self.region(offset, count, 1)
            values = []
            for _ in range(count):
                item, offset = self.value(offset, depth + 1)
                values.append(item)
            return values, offset
        if kind == 0x1d:
            require(arg == 0, 'invalid annotation encoding')
            return self.annotation(offset, depth + 1)
        if kind in (0x1e, 0x1f):
            require(arg <= (1 if kind == 0x1f else 0), 'invalid null/bool encoding')
            return (bool(arg) if kind == 0x1f else None), offset
        widths = {0: 1, 2: 2, 3: 2, 4: 4, 6: 8, 0x10: 4, 0x11: 8,
                  0x15: 4, 0x16: 4, 0x17: 4, 0x18: 4, 0x19: 4, 0x1a: 4, 0x1b: 4}
        require(kind in widths and arg < widths[kind], 'invalid encoded value')
        self.region(offset, arg + 1, 1)
        number = int.from_bytes(self.data[offset:offset + arg + 1], 'little', signed=kind in (0, 2, 4, 6))
        offset += arg + 1
        if kind == 0x17:
            number = self.at(self.strings, number)
        elif kind == 0x18:
            number = self.at(self.types, number)
        elif kind == 0x1b:
            number = ('enum', *self.at(self.fields, number))
        return number, offset

    def annotation_set(self, offset):
        if not offset:
            return {}
        result = {}
        for o in self.region(offset + 4, self.u32(offset), 4):
            start = self.u32(o)
            self.region(start, 1, 1)
            visibility = self.data[start]
            require(visibility in (0, 1, 2), 'invalid annotation visibility')
            (name, values), _ = self.annotation(start + 1)
            require(name not in result, 'duplicate annotation')
            result[name] = (visibility, values)
        return result

    def directory(self, offset):
        if not offset:
            return {}, {}
        class_annotations = self.annotation_set(self.u32(offset))
        fields, methods, parameters = (self.u32(offset + n) for n in (4, 8, 12))
        self.region(offset + 16, fields + methods + parameters, 8)
        result = {}
        for o in self.region(offset + 16, fields, 8):
            index = self.u32(o)
            self.at(self.fields, index)
            require(index not in result, 'duplicate annotated field')
            result[index] = self.annotation_set(self.u32(o + 4))
        return class_annotations, result

    def class_data(self, offset, owner):
        if not offset:
            return {}, []
        counts = []
        for _ in range(4):
            count, offset = self.leb(offset)
            counts.append(count)
        self.region(offset, sum(counts), 1)
        fields, methods = {}, []
        for count in counts[:2]:
            index = 0
            for _ in range(count):
                delta, offset = self.leb(offset)
                index += delta
                flags, offset = self.leb(offset)
                declaring, name = self.at(self.fields, index)
                require(declaring == owner and index not in fields, 'invalid field definition')
                fields[index] = (name, flags)
        seen = set()
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                delta, offset = self.leb(offset)
                index += delta
                flags, offset = self.leb(offset)
                code, offset = self.leb(offset)
                declaring, name, proto = self.at(self.methods, index)
                require(declaring == owner and index not in seen, 'invalid method definition')
                seen.add(index)
                if code:
                    require(code % 4 == 0, 'unaligned method code')
                    self.region(code, 16, 1)
                methods.append((name, proto, flags, code))
        return fields, methods


def inspect_apksig_dex(dex_files):
    """Require the reflection contract in actual definitions across all APK DEX files."""
    classes = {}
    require(bool(dex_files), 'no DEX files')
    for data in dex_files:
        parsed = Dex(data)
        require(not classes.keys() & parsed.classes.keys(), 'duplicate multidex class')
        classes.update(parsed.classes)
    for model, names in MODELS.items():
        descriptor = PREFIX + 'pkcs7/' + model + ';'
        require(descriptor in classes, 'missing model definition ' + model)
        flags, fields, methods, annotations, field_annotations = classes[descriptor]
        require(flags & 1 and not flags & (0x200 | 0x400), 'nonpublic/abstract model ' + model)
        require(any(name == '<init>' and proto == ('V', []) and flags & 1 and flags & 0x10000 and code
                    for name, proto, flags, code in methods), 'missing public noarg constructor ' + model)
        require(annotations.get(ASN_CLASS, (None,))[0] == 1 and
                annotations[ASN_CLASS][1].get('type') == ('enum', PREFIX + 'asn1/Asn1Type;', 'SEQUENCE'),
                'missing runtime Asn1Class ' + model)
        by_name = {name: (index, flags) for index, (name, flags) in fields.items()}
        for name in names:
            require(name in by_name, 'missing field ' + model + '.' + name)
            index, flags = by_name[name]
            require(flags & 1 and not flags & 8, 'nonpublic/static model field ' + name)
            annotations = field_annotations.get(index, {})
            require(annotations.get(ASN_FIELD, (None,))[0] == 1 and
                    'type' in annotations[ASN_FIELD][1] and 'index' in annotations[ASN_FIELD][1],
                    'missing runtime Asn1Field ' + model + '.' + name)
            if (model, name) in GENERICS:
                signature = annotations.get(SIGNATURE, (None, {}))
                parts = signature[1].get('value')
                require(signature[0] == 2 and isinstance(parts, list) and all(isinstance(p, str) for p in parts)
                        and ''.join(parts) == 'Ljava/util/List<' + GENERICS[model, name] + '>;',
                        'missing generic Signature ' + model + '.' + name)
    for descriptor, names in ((ASN_CLASS, {'type'}), (ASN_FIELD, {'type', 'index', 'cls', 'tagging', 'tagNumber', 'optional', 'elementType'})):
        require(descriptor in classes, 'missing annotation definition ' + descriptor)
        flags, _, methods, annotations, _ = classes[descriptor]
        require(flags & 0x2000 and flags & 0x200 and flags & 1, 'invalid annotation definition')
        require(names <= {name for name, proto, flags, code in methods
                          if not proto[1] and flags & 1 and flags & 0x400 and not code}, 'missing annotation methods')
        require(annotations.get(RETENTION) == (1, {'value': ('enum', 'Ljava/lang/annotation/RetentionPolicy;', 'RUNTIME')}),
                'missing runtime retention')
    defaults = classes[ASN_FIELD][3].get(DEFAULT)
    expected = {'index': 0, 'cls': ('enum', PREFIX + 'asn1/Asn1TagClass;', 'AUTOMATIC'),
                'tagging': ('enum', PREFIX + 'asn1/Asn1Tagging;', 'NORMAL'), 'tagNumber': -1,
                'optional': False, 'elementType': ('enum', PREFIX + 'asn1/Asn1Type;', 'ANY')}
    require(defaults == (2, {'value': (ASN_FIELD, expected)}), 'missing or changed Asn1Field defaults')
    # Asn1Class.type is required and intentionally has no default in apksig 9.2.1.
    require(DEFAULT not in classes[ASN_CLASS][3], 'unexpected Asn1Class defaults')
