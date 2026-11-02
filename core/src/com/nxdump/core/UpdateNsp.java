package com.nxdump.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Exports one update (patch) title as a standalone NSP.
 *
 * The NCAs are copied byte for byte (still encrypted, exactly like the original dump), together with the
 * matching ticket / certificate / CNMT xml. No base game is needed, and nothing is decrypted or re-signed.
 */
public final class UpdateNsp {
    private UpdateNsp() {}

    /** What will be written. Built by plan(), written by export(). */
    public static final class Plan {
        public final long titleId, version;
        public final String fileName;
        public final List<Container.Item> items;
        public final List<String> warnings;
        /** Total size of the finished NSP in bytes. */
        public final long totalSize;
        final byte[] header;

        Plan(long titleId, long version, String fileName, List<Container.Item> items, List<String> warnings, byte[] header, long totalSize) {
            this.titleId = titleId;
            this.version = version;
            this.fileName = fileName;
            this.items = items;
            this.warnings = warnings;
            this.header = header;
            this.totalSize = totalSize;
        }
    }

    /**
     * Finds the update inside the container and decides which files belong to it.
     *
     * @param titleId update title id, or 0 for "the first (highest version) update in the file"
     * @param version update version to pick when the file holds several of the same title, or -1 for any
     */
    public static Plan plan(Container c, Keys keys, long titleId, long version) throws IOException {
        List<Analyzer.Info> infos = Analyzer.analyze(c, keys);

        Analyzer.Info upd = null;

        for (Analyzer.Info i : infos) {
            if (i.kind != Analyzer.UPDATE || i.metaNca == null) {
                continue;
            }

            if (titleId != 0 && i.titleId != titleId) {
                continue;
            }

            if (version >= 0 && i.version != version) {
                continue;
            }

            if (upd == null || i.version > upd.version) {
                upd = i;
            }
        }

        if (upd == null) {
            throw new NxException("No update found in " + c.fileName);
        }

        List<Nca> ncas = c.openNcas(keys, null);
        List<String> warnings = new ArrayList<>();
        List<Container.Item> picked = new ArrayList<>();
        Set<Container.Item> seen = new HashSet<>();

        // 1) NCAs: the meta NCA plus everything its content records list.
        List<String> wanted = new ArrayList<>();
        wanted.add(upd.metaNca);
        wanted.addAll(upd.contentNcas);

        List<Container.Item> ncaItems = new ArrayList<>();

        for (String w : wanted) {
            Container.Item it = findItem(c, w);

            if (it == null) {
                if (w.equalsIgnoreCase(upd.programNca) || w.equalsIgnoreCase(upd.controlNca) || w.equals(upd.metaNca)) {
                    throw new NxException(
                            "This update is incomplete: " + w + " is missing from " + c.fileName
                    );
                }

                warnings.add("Optional content " + w + " is not in the file and was skipped");
                continue;
            }

            if (seen.add(it)) {
                ncaItems.add(it);
            }
        }

        if (upd.contentNcas.isEmpty()) {
            // CNMT without a content list: fall back to the NCA headers.
            for (Nca n : ncas) {
                if (n.titleId == upd.titleId) {
                    Container.Item it = findItem(c, n.name);

                    if (it != null && seen.add(it)) {
                        ncaItems.add(it);
                    }
                }
            }
        }

        // 2) Ticket + certificate for every rights id used by the selected NCAs.
        Set<String> rights = new HashSet<>();

        for (Nca n : ncas) {
            if (!n.hasRightsId()) {
                continue;
            }

            Container.Item it = findItem(c, n.name);

            if (it != null && ncaItems.contains(it)) {
                rights.add(n.rightsIdHex().toLowerCase(Locale.ROOT));
            }
        }

        List<Container.Item> tikItems = new ArrayList<>();
        List<Container.Item> certItems = new ArrayList<>();

        for (String rid : rights) {
            Container.Item tik = null;

            for (Container.Item it : c.items) {
                if (it.name.toLowerCase(Locale.ROOT).endsWith(".tik") && rid.equals(c.ticketRightsId(it))) {
                    tik = it;
                    break;
                }
            }

            if (tik == null) {
                warnings.add(
                        "No ticket (.tik) for rights id " + rid + " in the file. " +
                        "The NSP may not install without it"
                );
                continue;
            }

            if (seen.add(tik)) {
                tikItems.add(tik);
            }

            String stem = tik.name.substring(0, tik.name.length() - 4);
            Container.Item cert = findItem(c, stem + ".cert");

            if (cert == null) {
                warnings.add("No certificate (.cert) found for ticket " + tik.name);
            } else if (seen.add(cert)) {
                certItems.add(cert);
            }
        }

        // 3) CNMT xml next to the meta NCA ("<id>.cnmt.nca" -> "<id>.cnmt.xml").
        Container.Item xml = null;

        if (upd.metaNca.toLowerCase(Locale.ROOT).endsWith(".nca")) {
            xml = findItem(c, upd.metaNca.substring(0, upd.metaNca.length() - 4) + ".xml");
        }

        // Tickets first (streaming installers want them before the NCAs), then NCAs, then the xml.
        picked.addAll(tikItems);
        picked.addAll(certItems);
        picked.addAll(ncaItems);

        if (xml != null && seen.add(xml)) {
            picked.add(xml);
        }

        if (picked.isEmpty()) {
            throw new NxException("Nothing to export");
        }

        byte[] header = buildHeader(picked);
        long total = header.length;

        for (Container.Item it : picked) {
            total += it.size;
        }

        String name = upd.name == null || upd.name.trim().isEmpty() ? "update" : upd.name.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();

        if (name.isEmpty()) {
            name = "update";
        }

        String fileName = String.format(
                Locale.ROOT, "%s [%016X][v%d].nsp", name, upd.titleId, upd.version
        );

        return new Plan(upd.titleId, upd.version, fileName, picked, warnings, header, total);
    }

    /** Streams the planned NSP into sink (one file, named plan.fileName). */
    public static void export(
            Container c,
            Plan plan,
            Dumper.Sink sink,
            Dumper.Progress prog
    ) throws IOException {
        if (prog.cancelled()) {
            throw new InterruptedIOException("Cancelled");
        }

        byte[] buf = new byte[4 << 20]; // 4 MB buffer for high throughput
        long done = 0;

        prog.update(plan.fileName, 0, plan.totalSize);

        try (OutputStream out = sink.open(plan.fileName)) {
            out.write(plan.header);
            done += plan.header.length;

            for (Container.Item it : plan.items) {
                Source src = c.itemSource(it);
                long p = 0;

                while (p < it.size) {
                    if (prog.cancelled()) {
                        throw new InterruptedIOException("Cancelled");
                    }

                    int n = (int) Math.min(buf.length, it.size - p);

                    src.read(p, buf, 0, n);
                    out.write(buf, 0, n);

                    p += n;
                    done += n;

                    prog.update(it.name, done, plan.totalSize);
                }
            }
        }

        // The file only gets its final name once everything is on disk.
        sink.commit(plan.fileName, plan.totalSize);
        prog.update("", plan.totalSize, plan.totalSize);
    }

    private static Container.Item findItem(Container c, String name) {
        for (Container.Item it : c.items) {
            if (it.name.equalsIgnoreCase(name)) {
                return it;
            }
        }

        return null;
    }

    /** PFS0 header: magic, counts, entry table and string table, padded so the data starts on a 0x20 boundary. */
    static byte[] buildHeader(List<Container.Item> items) {
        int n = items.size();

        ByteArrayOutputStream names = new ByteArrayOutputStream();
        int[] nameOff = new int[n];

        for (int i = 0; i < n; i++) {
            nameOff[i] = names.size();

            byte[] b = items.get(i).name.getBytes(StandardCharsets.UTF_8);

            names.write(b, 0, b.length);
            names.write(0);
        }

        int strSize = names.size();
        int fixed = 16 + 0x18 * n;
        int pad = (0x20 - ((fixed + strSize) & 0x1F)) & 0x1F;
        strSize += pad;

        byte[] h = new byte[fixed + strSize];

        h[0] = 'P';
        h[1] = 'F';
        h[2] = 'S';
        h[3] = '0';

        putLe32(h, 4, n);
        putLe32(h, 8, strSize);

        long off = 0;

        for (int i = 0; i < n; i++) {
            int o = 16 + 0x18 * i;
            long size = items.get(i).size;

            putLe64(h, o, off);
            putLe64(h, o + 8, size);
            putLe32(h, o + 16, nameOff[i]);

            off += size;
        }

        byte[] nb = names.toByteArray();

        System.arraycopy(nb, 0, h, fixed, nb.length);

        return h;
    }

    private static void putLe32(byte[] b, int o, int v) {
        for (int i = 0; i < 4; i++) {
            b[o + i] = (byte) (v >>> (8 * i));
        }
    }

    private static void putLe64(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) {
            b[o + i] = (byte) (v >>> (8 * i));
        }
    }
}
