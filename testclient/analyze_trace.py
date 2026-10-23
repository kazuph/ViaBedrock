"""Decode the creative catalog layout from captured protocol 2193 packets.

Does not infer or substitute unknown layouts: remaining bytes fail the run.
"""
import json
import re
import struct
import sys


class Reader:
    def __init__(self, raw):
        self.raw = raw
        self.offset = 0

    def take(self, size):
        assert 0 <= size <= len(self.raw) - self.offset
        data = self.raw[self.offset:self.offset + size]
        self.offset += size
        return data

    def uint(self):
        value = 0
        for shift in range(0, 35, 7):
            part = self.take(1)[0]
            value |= (part & 127) << shift
            if part < 128:
                return value
        raise ValueError("Varint exceeds 32 bits")

    def sint(self):
        value = self.uint()
        return (value >> 1) ^ -(value & 1)

    def string(self):
        return self.take(self.uint()).decode("utf-8")

    def instance(self):
        runtime_id = self.sint()
        count = struct.unpack("<H", self.take(2))[0]
        aux = self.uint()
        block = self.sint()
        userdata = self.take(self.uint())
        return dict(runtime_id=runtime_id, count=count, aux=aux,
                    block=block, userdata=userdata.hex())


def catalog(raw):
    reader = Reader(raw)
    groups = []
    for _ in range(reader.uint()):
        groups.append(dict(category=reader.take(1)[0], name=reader.string(), icon=reader.instance()))
    items = []
    for _ in range(reader.uint()):
        items.append(dict(net_id=reader.uint(), item=reader.instance(), group=reader.uint()))
    assert reader.offset == len(raw), f"Unread bytes: {len(raw) - reader.offset}"
    assert len({item['net_id'] for item in items}) == len(items)
    return dict(bytes=len(raw), groups=groups, items=items)


if __name__ == "__main__":
    text = open(sys.argv[1]).read()
    captures = re.findall(r"packet=CREATIVE_CONTENT payload=([0-9a-f]+)", text)
    if not captures:
        raise ValueError("No captured creative content")
    result = catalog(bytes.fromhex(captures[-1]))
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    print()
