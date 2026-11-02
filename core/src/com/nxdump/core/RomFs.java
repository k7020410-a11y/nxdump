package com.nxdump.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RomFs {
    private RomFs() {}

    /**
     * Real tables are a few MB at most.
     * This prevents absurd values from exhausting phone memory.
     */
    private static final long MAX_TABLE = 64L << 20;

    private static final int DIR_ENTRY_MIN = 24;
    private static final int FILE_ENTRY_MIN = 32;

    public static final class File {
        public final String path;
        public final long offset;
        public final long size;

        File(String path, long offset, long size) {
            this.path = path;
            this.offset = offset;
            this.size = size;
        }
    }

    private static final class Walker {
        final byte[] dirs;
        final byte[] files;
        final long dataOff;
        final long avail;
        final List<File> out = new ArrayList<>();

        final int maxDirs;
        final int maxFiles;

        int dirCount;
        int fileCount;

        Walker(
                byte[] dirs,
                byte[] files,
                long dataOff,
                long imgSize
        ) {
            this.dirs = dirs;
            this.files = files;
            this.dataOff = dataOff;
            this.avail = imgSize - dataOff;
            this.maxDirs = dirs.length / DIR_ENTRY_MIN;
            this.maxFiles = files.length / FILE_ENTRY_MIN;
        }
    }

    public static List<File> list(Source img) throws IOException {
        long imgSize = img.size();

        if (imgSize < 0x50) {
            throw new NxException(
                    "Not a RomFS image (too small)"
            );
        }

        byte[] h = img.read(0, 0x50);

        if (Bin.le64(h, 0) != 0x50) {
            throw new NxException(
                    "Not a RomFS image (bad header)"
            );
        }

        long dirOff = Bin.le64(h, 0x18);
        long dirSize = Bin.le64(h, 0x20);
        long fileOff = Bin.le64(h, 0x38);
        long fileSize = Bin.le64(h, 0x40);
        long dataOff = Bin.le64(h, 0x48);

        if (
                dirSize < DIR_ENTRY_MIN ||
                dirSize > MAX_TABLE ||
                fileSize < 0 ||
                fileSize > MAX_TABLE
        ) {
            throw new NxException("Corrupt RomFS tables");
        }

        checkRange(
                dirOff,
                dirSize,
                imgSize,
                "directory table"
        );

        checkRange(
                fileOff,
                fileSize,
                imgSize,
                "file table"
        );

        if (dataOff < 0 || dataOff > imgSize) {
            throw new NxException(
                    "Invalid RomFS data offset"
            );
        }

        byte[] dirs = img.read(dirOff, (int) dirSize);
        byte[] files = img.read(fileOff, (int) fileSize);

        Walker w = new Walker(
                dirs,
                files,
                dataOff,
                imgSize
        );

        walk(w, 0, "", 0);

        return w.out;
    }

    private static void checkRange(
            long off,
            long size,
            long limit,
            String what
    ) throws NxException {
        if (
                off < 0 ||
                size < 0 ||
                off > limit ||
                size > limit - off
        ) {
            throw new NxException(
                    "RomFS " + what +
                    " lies outside the image"
            );
        }
    }

    private static void walk(
            Walker w,
            int dir,
            String path,
            int depth
    ) throws IOException {
        if (depth > 256) {
            throw new NxException(
                    "RomFS directory nesting too deep"
            );
        }

        if (++w.dirCount > w.maxDirs) {
            throw new NxException(
                    "RomFS directory tree has a loop (corrupt)"
            );
        }

        try {
            for (
                    int f = Bin.le32(w.dirs, dir + 12);
                    f != -1;
                    f = Bin.le32(w.files, f + 4)
            ) {
                if (
                        f < 0 ||
                        f > w.files.length - FILE_ENTRY_MIN
                ) {
                    throw new NxException(
                            "Invalid RomFS file pointer"
                    );
                }

                if (++w.fileCount > w.maxFiles) {
                    throw new NxException(
                            "RomFS file list has a loop (corrupt)"
                    );
                }

                int nl = Bin.le32(w.files, f + 28);

                if (
                        nl < 0 ||
                        nl > w.files.length -
                                (f + FILE_ENTRY_MIN)
                ) {
                    throw new NxException(
                            "Corrupt RomFS file name"
                    );
                }

                long off = Bin.le64(w.files, f + 8);
                long size = Bin.le64(w.files, f + 16);

                String name = Bin.cstr(
                        w.files,
                        f + FILE_ENTRY_MIN,
                        nl
                );

                if (
                        off < 0 ||
                        size < 0 ||
                        off > w.avail ||
                        size > w.avail - off
                ) {
                    throw new NxException(
                            "RomFS file \"" +
                            path +
                            name +
                            "\" lies outside the image"
                    );
                }

                w.out.add(
                        new File(
                                path + name,
                                w.dataOff + off,
                                size
                        )
                );
            }

            for (
                    int d = Bin.le32(w.dirs, dir + 8);
                    d != -1;
                    d = Bin.le32(w.dirs, d + 4)
            ) {
                if (
                        d < 0 ||
                        d > w.dirs.length - DIR_ENTRY_MIN
                ) {
                    throw new NxException(
                            "Invalid RomFS directory pointer"
                    );
                }

                int nl = Bin.le32(w.dirs, d + 20);

                if (
                        nl < 0 ||
                        nl > w.dirs.length -
                                (d + DIR_ENTRY_MIN)
                ) {
                    throw new NxException(
                            "Corrupt RomFS directory name"
                    );
                }

                walk(
                        w,
                        d,
                        path +
                                Bin.cstr(
                                        w.dirs,
                                        d + DIR_ENTRY_MIN,
                                        nl
                                ) +
                                "/",
                        depth + 1
                );
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new NxException(
                    "Corrupt RomFS directory tree"
            );
        }
    }
}
