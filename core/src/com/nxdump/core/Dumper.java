package com.nxdump.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Extracts ExeFS / RomFS, optionally with an update merged on top. */
public final class Dumper {
    private Dumper() {}

    public interface Sink {
        /**
         * Opens a stream for a new file.
         * The file only counts as finished once commit() is called.
         */
        OutputStream open(String relativePath)
                throws IOException;

        /**
         * True if this file was already extracted completely
         * by an earlier run.
         */
        default boolean isComplete(
                String relativePath,
                long size
        ) throws IOException {
            return false;
        }

        /**
         * Called after the stream from open() was closed
         * and all bytes were written.
         */
        default void commit(
                String relativePath,
                long size
        ) throws IOException {
        }
    }

    public interface Progress {
        void update(
                String file,
                long done,
                long total
        );

        boolean cancelled();
    }

    private static final class Job {
        final String path;
        final Source src;
        final long off;
        final long size;

        Job(
                String path,
                Source src,
                long off,
                long size
        ) {
            this.path = path;
            this.src = src;
            this.off = off;
            this.size = size;
        }
    }

    /** Header-based lookup fallback. */
    private static Nca findProgram(
            List<Nca> ncas,
            long appId,
            boolean patch
    ) {
        Nca best = null;

        for (Nca n : ncas) {
            if (
                    n.contentType != Nca.TYPE_PROGRAM ||
                    Analyzer.appIdOf(n.titleId) != appId
            ) {
                continue;
            }

            int low = (int) (n.titleId & 0x1FFF);

            boolean isPatch =
                    low >= 0x800 &&
                    low < 0x1000;

            if (isPatch != patch) {
                continue;
            }

            if (
                    best == null ||
                    n.titleId < best.titleId
            ) {
                best = n;
            }
        }

        return best;
    }

    /**
     * Finds the program NCA via the CNMT content list.
     * Falls back to NCA header heuristics.
     */
    private static Nca resolveProgram(
            Container c,
            Keys keys,
            long appId,
            boolean patch
    ) {
        List<Nca> ncas =
                c.openNcas(keys, null);

        int kind =
                patch
                        ? Analyzer.UPDATE
                        : Analyzer.BASE;

        for (Analyzer.Info i :
                Analyzer.analyze(c, keys)) {
            if (
                    i.kind == kind &&
                    i.appId == appId &&
                    i.programNca != null
            ) {
                for (Nca n : ncas) {
                    if (
                            n.name.equalsIgnoreCase(
                                    i.programNca
                            )
                    ) {
                        return n;
                    }
                }
            }
        }

        Nca n = findProgram(
                ncas,
                appId,
                patch
        );

        if (n != null) {
            return n;
        }

        Nca any = null;

        for (Nca x : ncas) {
            if (x.contentType != Nca.TYPE_PROGRAM) {
                continue;
            }

            if (
                    patch
                            ? x.isPatchRomFs()
                            : !x.isPatchRomFs() &&
                              Analyzer.appIdOf(
                                      x.titleId
                              ) == appId
            ) {
                any = x;
                break;
            }
        }

        return any;
    }

    /**
     * update may be null, or be the same Container as base.
     */
    public static void dump(
            Container base,
            Container update,
            long appId,
            boolean exefs,
            boolean romfs,
            Keys keys,
            Sink sink,
            Progress prog
    ) throws IOException {
        base.importTickets(keys);

        if (update != null) {
            update.importTickets(keys);
        }

        Nca baseProg =
                resolveProgram(
                        base,
                        keys,
                        appId,
                        false
                );

        if (baseProg == null) {
            throw new NxException(
                    "Base game program data not found " +
                    "in the selected file"
            );
        }

        Nca updProg = null;

        if (update != null) {
            updProg =
                    resolveProgram(
                            update,
                            keys,
                            appId,
                            true
                    );

            if (updProg == null) {
                throw new NxException(
                        "The selected update does not belong " +
                        "to this game"
                );
            }
        }

        List<Job> jobs =
                new ArrayList<>();

        if (exefs) {
            Nca n =
                    updProg != null
                            ? updProg
                            : baseProg;

            Nca.Section s =
                    n.section(Nca.FS_PFS);

            if (s == null) {
                throw new NxException(
                        "ExeFS section not found"
                );
            }

            Source d = n.openData(s);

            for (Pfs.Entry e : Pfs.parse(d)) {
                jobs.add(
                        new Job(
                                "exefs/" + e.name,
                                d,
                                e.offset,
                                e.size
                        )
                );
            }
        }

        if (romfs) {
            Source img;

            Nca.Section bs =
                    baseProg.section(Nca.FS_ROMFS);

            Nca.Section us =
                    updProg != null
                            ? updProg.section(
                                    Nca.FS_ROMFS
                            )
                            : null;

            if (
                    us != null &&
                    us.encType == Nca.ENC_CTR_EX
            ) {
                if (bs == null) {
                    throw new NxException(
                            "Base game has no RomFS to patch"
                    );
                }

                img =
                        updProg.openPatchedRomFs(
                                us,
                                baseProg.openSection(bs)
                        );
            } else if (us != null) {
                img = updProg.openData(us);
            } else if (bs != null) {
                img = baseProg.openData(bs);
            } else {
                throw new NxException(
                        "RomFS section not found"
                );
            }

            for (RomFs.File f : RomFs.list(img)) {
                jobs.add(
                        new Job(
                                "romfs/" + f.path,
                                img,
                                f.offset,
                                f.size
                        )
                );
            }
        }

        runJobs(jobs, sink, prog);
    }

    /** Copies every job to the sink (skipping files an earlier run already finished). */
    private static void runJobs(
            List<Job> jobs,
            Sink sink,
            Progress prog
    ) throws IOException {
        if (jobs.isEmpty()) {
            throw new NxException(
                    "Nothing to extract"
            );
        }

        if (prog.cancelled()) {
            throw new InterruptedIOException(
                    "Cancelled"
            );
        }

        long total = 0;

        for (Job j : jobs) {
            total += j.size;
        }

        long done = 0;
        byte[] buf = new byte[4 << 20]; // 4 MB buffer for high throughput

        for (Job j : jobs) {
            if (prog.cancelled()) {
                throw new InterruptedIOException(
                        "Cancelled"
                );
            }

            if (sink.isComplete(j.path, j.size)) {
                done += j.size;
                prog.update(
                        j.path,
                        done,
                        total
                );
                continue;
            }

            prog.update(
                    j.path,
                    done,
                    total
            );

            try (
                    OutputStream out =
                            sink.open(j.path)
            ) {
                long p = 0;
                while (p < j.size) {
                    if (prog.cancelled()) {
                        throw new InterruptedIOException(
                                "Cancelled"
                        );
                    }

                    int n =
                            (int) Math.min(
                                    buf.length,
                                    j.size - p
                            );

                    j.src.read(
                            j.off + p,
                            buf,
                            0,
                            n
                    );

                    out.write(
                            buf,
                            0,
                            n
                    );

                    p += n;
                    done += n;

                    prog.update(
                            j.path,
                            done,
                            total
                    );
                }
            }

            // The file gets its final name only now.
            sink.commit(
                    j.path,
                    j.size
            );
        }

        prog.update(
                "",
                total,
                total
        );
    }

    /** Result of dumpUpdateOnly(). */
    public static final class UpdateResult {
        /** RomFS files that could not be extracted because their data lives in the base game. */
        public int skippedFiles;
        /** RomFS files that were extracted. */
        public int romfsFiles;
        /** Human readable remark, or null. */
        public String note;
    }

    /**
     * Extracts an update on its own (no base game): the full decrypted ExeFS, and from the RomFS every file whose
     * data is stored completely inside the update. Files that need the base game are skipped and listed in
     * "romfs_skipped.txt".
     *
     * @param titleId update title id, or 0 for any
     * @param version update version, or -1 for any
     */
    public static UpdateResult dumpUpdateOnly(
            Container c,
            long titleId,
            long version,
            boolean exefs,
            boolean romfs,
            Keys keys,
            Sink sink,
            Progress prog
    ) throws IOException {
        c.importTickets(keys);

        Analyzer.Info upd = null;

        for (Analyzer.Info i : Analyzer.analyze(c, keys)) {
            if (
                    i.kind != Analyzer.UPDATE ||
                    (titleId != 0 && i.titleId != titleId) ||
                    (version >= 0 && i.version != version)
            ) {
                continue;
            }

            if (upd == null || i.version > upd.version) {
                upd = i;
            }
        }

        if (upd == null) {
            throw new NxException(
                    "No update found in " + c.fileName
            );
        }

        Nca prog0 = null;

        if (upd.programNca != null) {
            for (Nca n : c.openNcas(keys, null)) {
                if (n.name.equalsIgnoreCase(upd.programNca)) {
                    prog0 = n;
                    break;
                }
            }
        }

        if (prog0 == null) {
            prog0 = resolveProgram(c, keys, upd.appId, true);
        }

        if (prog0 == null) {
            throw new NxException(
                    "The update's program data was not found in the file"
            );
        }

        UpdateResult res = new UpdateResult();
        List<Job> jobs = new ArrayList<>();

        if (exefs) {
            Nca.Section s = prog0.section(Nca.FS_PFS);

            if (s == null) {
                throw new NxException("ExeFS section not found");
            }

            Source d = prog0.openData(s);

            for (Pfs.Entry e : Pfs.parse(d)) {
                jobs.add(new Job("exefs/" + e.name, d, e.offset, e.size));
            }
        }

        if (romfs) {
            Nca.Section us = prog0.section(Nca.FS_ROMFS);

            if (us == null) {
                res.note = "This update has no RomFS.";
            } else if (us.encType != Nca.ENC_CTR_EX) {
                // Not a patch: the update carries a complete RomFS.
                Source img = prog0.openData(us);

                for (RomFs.File f : RomFs.list(img)) {
                    jobs.add(new Job("romfs/" + f.path, img, f.offset, f.size));
                    res.romfsFiles++;
                }
            } else {
                Nca.PatchOnly po = prog0.openPatchOnlyRomFs(us);
                List<RomFs.File> files;

                try {
                    files = RomFs.list(po.image);
                } catch (IOException e) {
                    throw new NxException(
                            "This update's RomFS file table is stored in the base game. " +
                            "Add the base game and use Merge update to extract the RomFS"
                    );
                }

                StringBuilder skipped = new StringBuilder();

                for (RomFs.File f : files) {
                    if (po.covers(f.offset, f.size)) {
                        jobs.add(new Job("romfs/" + f.path, po.image, f.offset, f.size));
                        res.romfsFiles++;
                    } else {
                        res.skippedFiles++;
                        skipped.append(f.path).append('\n');
                    }
                }

                if (res.skippedFiles > 0) {
                    byte[] list = (
                            "RomFS files that need the base game (not extracted):\n" + skipped
                    ).getBytes(java.nio.charset.StandardCharsets.UTF_8);

                    jobs.add(new Job("romfs_skipped.txt", new Source.Bytes(list), 0, list.length));

                    res.note = res.skippedFiles + " RomFS file(s) need the base game and were skipped " +
                            "(listed in romfs_skipped.txt). " + res.romfsFiles + " extracted.";
                }
            }
        }

        runJobs(jobs, sink, prog);

        return res;
    }
}
