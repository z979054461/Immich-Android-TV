#!/usr/bin/env python3
"""Byte-level synthetic DEX fixtures; never reads user APKs, keys or network."""
import copy
import struct
import unittest

from check_apksig_dex import (ASN_CLASS, ASN_FIELD, DEFAULT, GENERICS, MODELS,
                              PREFIX, RETENTION, SIGNATURE, inspect_apksig_dex)


def leb(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def fixture():
    models = {}
    for model, fields in MODELS.items():
        descriptor = PREFIX + 'pkcs7/' + model + ';'
        spec = {'flags': 1, 'fields': {}, 'methods': [('<init>', 0x10001)],
                'annotations': {ASN_CLASS: (1, {'type': ('enum', PREFIX + 'asn1/Asn1Type;', 'SEQUENCE')})}}
        for index, name in enumerate(fields):
            annotations = {ASN_FIELD: (1, {'index': index, 'type': ('enum', PREFIX + 'asn1/Asn1Type;', 'ANY')})}
            if (model, name) in GENERICS:
                annotations[SIGNATURE] = (2, {'value': ['Ljava/util/List<', GENERICS[model, name], '>;']})
            spec['fields'][name] = {'flags': 1, 'annotations': annotations}
        models[descriptor] = spec
    retention = {RETENTION: (1, {'value': ('enum', 'Ljava/lang/annotation/RetentionPolicy;', 'RUNTIME')})}
    models[ASN_CLASS] = {'flags': 0x2601, 'fields': {}, 'methods': [('type', 0x401)], 'annotations': copy.deepcopy(retention)}
    models[ASN_FIELD] = {'flags': 0x2601, 'fields': {}, 'methods': [(name, 0x401) for name in
                         ('type', 'index', 'cls', 'tagging', 'tagNumber', 'optional', 'elementType')],
                        'annotations': copy.deepcopy(retention)}
    models[ASN_FIELD]['annotations'][DEFAULT] = (2, {'value': (ASN_FIELD, {
        'index': 0, 'cls': ('enum', PREFIX + 'asn1/Asn1TagClass;', 'AUTOMATIC'),
        'tagging': ('enum', PREFIX + 'asn1/Asn1Tagging;', 'NORMAL'), 'tagNumber': -1,
        'optional': False, 'elementType': ('enum', PREFIX + 'asn1/Asn1Type;', 'ANY')})})
    return models


def dex(specs, defined=None):
    """Build real table/definition/annotation offsets, including references not defined here."""
    if defined is None:
        defined = set(specs)
    strings = {'V', 'I', 'Ljava/lang/Object;'}
    types = {'V', 'I', 'Ljava/lang/Object;'}
    enum_fields = set()

    def annotation_refs(name, values):
        types.add(name)
        strings.add(name)
        for key, value in values.items():
            strings.add(key)
            value_refs(value)

    def value_refs(value):
        if isinstance(value, str):
            strings.add(value)
        elif isinstance(value, list):
            for item in value:
                value_refs(item)
        elif isinstance(value, tuple):
            if value[0] == 'enum':
                _, owner, name = value
                enum_fields.add((owner, name))
                types.add(owner)
                strings.update((owner, name))
            else:
                annotation_refs(*value)

    for owner, spec in specs.items():
        types.add(owner)
        strings.add(owner)
        strings.update(name for name, _ in spec['methods'])
        for name, field in spec['fields'].items():
            strings.add(name)
            for annotation, (_, values) in field['annotations'].items():
                annotation_refs(annotation, values)
        for annotation, (_, values) in spec['annotations'].items():
            annotation_refs(annotation, values)
    strings.update(types)
    strings = sorted(strings)
    types = sorted(types)
    si = {name: i for i, name in enumerate(strings)}
    ti = {name: i for i, name in enumerate(types)}
    fields = sorted(enum_fields | {(owner, name) for owner, spec in specs.items() for name in spec['fields']})
    fi = {field: i for i, field in enumerate(fields)}
    methods = [(owner, name, flags) for owner, spec in sorted(specs.items()) for name, flags in spec['methods']]
    method_ids = {(owner, name): i for i, (owner, name, _) in enumerate(methods)}
    defined = sorted(defined)
    data = bytearray(112)

    def reserve(count, width):
        start = len(data)
        data.extend(b'\0' * (count * width))
        return start

    def add(raw, alignment=1):
        data.extend(b'\0' * (-len(data) % alignment))
        start = len(data)
        data.extend(raw)
        return start

    def put(offset, *values):
        struct.pack_into('<' + 'I' * len(values), data, offset, *values)

    string_table = reserve(len(strings), 4)
    type_table = reserve(len(types), 4)
    proto_table = reserve(1, 12)
    field_table = reserve(len(fields), 8)
    method_table = reserve(len(methods), 8)
    class_table = reserve(len(defined), 32)
    for i, text in enumerate(strings):
        put(string_table + i * 4, add(leb(len(text)) + text.encode() + b'\0'))
    for i, name in enumerate(types):
        put(type_table + i * 4, si[name])
    put(proto_table, si['V'], ti['V'], 0)
    for i, (owner, name) in enumerate(fields):
        struct.pack_into('<HHI', data, field_table + i * 8, ti[owner], ti['I'], si[name])
    for i, (owner, name, _) in enumerate(methods):
        struct.pack_into('<HHI', data, method_table + i * 8, ti[owner], 0, si[name])
    code = add(struct.pack('<HHHHIIH', 1, 1, 0, 0, 0, 1, 0x0e), 4)

    def encoded_annotation(name, values):
        result = leb(ti[name]) + leb(len(values))
        for key in sorted(values, key=si.__getitem__):
            result += leb(si[key]) + encoded_value(values[key])
        return result

    def encoded_value(value):
        if isinstance(value, bool):
            return bytes([0x1f | (int(value) << 5)])
        if isinstance(value, int):
            return b'\x64' + struct.pack('<i', value)
        if isinstance(value, str):
            return b'\x77' + struct.pack('<I', si[value])
        if isinstance(value, list):
            return b'\x1c' + leb(len(value)) + b''.join(encoded_value(item) for item in value)
        if value[0] == 'enum':
            return b'\x7b' + struct.pack('<I', fi[value[1:]])
        return b'\x1d' + encoded_annotation(*value)

    def annotation_set(annotations):
        entries = [add(bytes([visibility]) + encoded_annotation(name, values))
                   for name, (visibility, values) in sorted(annotations.items(), key=lambda pair: ti[pair[0]])]
        return add(struct.pack('<I', len(entries)) + b''.join(struct.pack('<I', entry) for entry in entries), 4)

    for i, owner in enumerate(defined):
        spec = specs[owner]
        annotations = annotation_set(spec['annotations'])
        annotated_fields = [(fi[owner, name], annotation_set(field['annotations'])) for name, field in spec['fields'].items()]
        directory = add(struct.pack('<IIII', annotations, len(annotated_fields), 0, 0) +
                        b''.join(struct.pack('<II', *pair) for pair in sorted(annotated_fields)), 4)
        direct = [(method_ids[owner, name], flags) for name, flags in spec['methods'] if name == '<init>']
        virtual = [(method_ids[owner, name], flags) for name, flags in spec['methods'] if name != '<init>']
        body = leb(0) + leb(len(spec['fields'])) + leb(len(direct)) + leb(len(virtual))
        previous = 0
        for name in sorted(spec['fields'], key=lambda n: fi[owner, n]):
            index = fi[owner, name]
            body += leb(index - previous) + leb(spec['fields'][name]['flags'])
            previous = index
        for group in (direct, virtual):
            previous = 0
            for index, flags in sorted(group):
                body += leb(index - previous) + leb(flags) + leb(0 if flags & 0x400 else code)
                previous = index
        class_data = add(body)
        put(class_table + i * 32, ti[owner], spec['flags'], ti['Ljava/lang/Object;'], 0, 0xffffffff, directory, class_data, 0)
    data[:8] = b'dex\n035\0'
    put(32, len(data), 112, 0x12345678)
    for header, count, start in ((56, len(strings), string_table), (64, len(types), type_table), (72, 1, proto_table),
                                (80, len(fields), field_table), (88, len(methods), method_table), (96, len(defined), class_table)):
        put(header, count, start)
    return bytes(data)


class ApksigDexAuditTest(unittest.TestCase):
    def test_complete_structures_pass(self):
        inspect_apksig_dex([dex(fixture())])

    def test_multidex_union_passes(self):
        specs = fixture()
        names = sorted(specs)
        inspect_apksig_dex([dex(specs, names[:2]), dex(specs, names[2:])])

    def test_references_without_definition_fail(self):
        specs = fixture()
        for name in specs:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'definition'):
                inspect_apksig_dex([dex(specs, set(specs) - {name})])

    def test_duplicates_and_empty_fail(self):
        data = dex(fixture())
        for files in ([], [data, data]):
            with self.assertRaises(ValueError):
                inspect_apksig_dex(files)

    def test_missing_renamed_or_nonpublic_fields_fail(self):
        for model, fields in MODELS.items():
            for name in fields:
                for change in ('remove', 'private', 'static'):
                    specs = fixture()
                    spec = specs[PREFIX + 'pkcs7/' + model + ';']['fields']
                    if change == 'remove':
                        del spec[name]
                    else:
                        spec[name]['flags'] = 2 if change == 'private' else 9
                    with self.subTest(model=model, field=name, change=change), self.assertRaises(ValueError):
                        inspect_apksig_dex([dex(specs)])

    def test_public_concrete_models_and_public_noarg_constructors_required(self):
        for model in MODELS:
            for change in ('missing', 'private', 'abstract-class'):
                specs = fixture()
                spec = specs[PREFIX + 'pkcs7/' + model + ';']
                if change == 'missing':
                    spec['methods'] = []
                elif change == 'private':
                    spec['methods'] = [('<init>', 0x10002)]
                else:
                    spec['flags'] = 0x401
                with self.subTest(model=model, change=change), self.assertRaises(ValueError):
                    inspect_apksig_dex([dex(specs)])

    def test_runtime_annotations_cannot_be_dropped_or_build_only(self):
        for location in ('class', 'field'):
            for visibility in (None, 0, 2):
                specs = fixture()
                spec = specs[PREFIX + 'pkcs7/SignedData;']
                annotations = spec['annotations'] if location == 'class' else spec['fields']['version']['annotations']
                name = ASN_CLASS if location == 'class' else ASN_FIELD
                if visibility is None:
                    del annotations[name]
                else:
                    annotations[name] = (visibility, annotations[name][1])
                with self.subTest(location=location, visibility=visibility), self.assertRaises(ValueError):
                    inspect_apksig_dex([dex(specs)])

    def test_generic_signatures_must_be_field_system_annotations(self):
        for model, name in GENERICS:
            for change in ('missing', 'wrong-type', 'wrong-visibility'):
                specs = fixture()
                annotations = specs[PREFIX + 'pkcs7/' + model + ';']['fields'][name]['annotations']
                if change == 'missing':
                    del annotations[SIGNATURE]
                elif change == 'wrong-type':
                    annotations[SIGNATURE][1]['value'] = ['Ljava/util/List<Ljava/lang/Object;>;']
                else:
                    annotations[SIGNATURE] = (1, annotations[SIGNATURE][1])
                with self.subTest(model=model, name=name, change=change), self.assertRaisesRegex(ValueError, 'Signature'):
                    inspect_apksig_dex([dex(specs)])

    def test_defaults_and_retention_required(self):
        for name in ('index', 'cls', 'tagging', 'tagNumber', 'optional', 'elementType'):
            specs = fixture()
            del specs[ASN_FIELD]['annotations'][DEFAULT][1]['value'][1][name]
            with self.subTest(default=name), self.assertRaisesRegex(ValueError, 'defaults'):
                inspect_apksig_dex([dex(specs)])
        for annotation in (ASN_FIELD, ASN_CLASS):
            specs = fixture()
            del specs[annotation]['annotations'][RETENTION]
            with self.assertRaisesRegex(ValueError, 'retention'):
                inspect_apksig_dex([dex(specs)])

    def test_annotation_method_definitions_required(self):
        for annotation in (ASN_FIELD, ASN_CLASS):
            specs = fixture()
            specs[annotation]['methods'] = []
            with self.assertRaisesRegex(ValueError, 'methods'):
                inspect_apksig_dex([dex(specs)])

    def test_malformed_headers_offsets_indices_and_truncation_fail(self):
        data = dex(fixture())
        for length in (0, 7, 111, len(data) - 1):
            with self.assertRaises(ValueError):
                inspect_apksig_dex([data[:length]])
        for offset, value in ((36, 0), (40, 0x78563412), (60, 0xffffffff), (64, 0xffffffff),
                              (struct.unpack_from('<I', data, 100)[0], 0xffffffff)):
            broken = bytearray(data)
            struct.pack_into('<I', broken, offset, value)
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                inspect_apksig_dex([bytes(broken)])


if __name__ == '__main__':
    unittest.main()
