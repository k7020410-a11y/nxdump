package com.nxdump.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** PFS0 (NSP, ExeFS) and HFS0 (XCI) partition parser. */
public final class Pfs {
    private Pfs() {}

    public static final class Entry {
        public final String name;
        /** Offset relative to the source passed to parse(). */
        public final long offset;
        public final long size;

        public Entry(
                String name,
                long offset,
                long size
        ) {
            this.name = name;
            this.offset = offset;
            this.size = size;
        }
    }

    public static List<Entry> parse(Source s)
            throws IOException {
        long srcSize = s.size();

        if (srcSize < 16) {
            throw new NxException(
                    "Not a PFS0/HFS0 partition"
            );
        }

        byte[] h = s.read(0, 16);

        boolean hfs = Bin.magic(h, 0, "HFS0");

        if (
                !hfs &&
                !Bin.magic(h, 0, "PFS0")
        ) {
            throw new NxException(
                    "Not a PFS0/HFS0 partition"
            );
        }

        int count = Bin.le32(h, 4);
        int strSize = Bin.le32(h, 8);
        int es = hfs ? 0x40 : 0x18;

        if (
                count < 0 ||
                count > 200000 ||
                strSize < 0 ||
                strSize > (1 << 24)
        ) {
            throw new NxException(
                    "Corrupt partition header"
            );
        }

        long tabLen =
                (long) count * es + strSize;

        long dataStart = 16L + tabLen;

        if (dataStart > srcSize) {
            throw new NxException(
                    "Partition header is larger than the file " +
                    "(truncated or corrupt)"
            );
        }

        byte[] tab = s.read(
                16,
                (int) tabLen
        );

        long avail = srcSize - dataStart;

        List<Entry> out =
                new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            int o = i * es;

            long off = Bin.le64(tab, o);
            long size = Bin.le64(tab, o + 8);
            int nameOff = Bin.le32(tab, o + 16);

            if (
                    nameOff < 0 ||
                    nameOff >= strSize
            ) {
                throw new NxException(
                        "Corrupt partition string table"
                );
            }

            String name = Bin.cstr(
                    tab,
                    count * es + nameOff,
                    strSize - nameOff
            );

            if (
                    off < 0 ||
                    size < 0 ||
                    off > avail ||
                    size > avail - off
            ) {
                throw new NxException(
                        "Partition entry \"" +
                        name +
                        "\" lies outside the file " +
                        "(truncated or corrupt)"
                );
            }

            out.add(
                    new Entry(
                            name,
                            dataStart + off,
                            size
                    )
            );
        }

        return out;
    }
}
