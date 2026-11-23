package net.mangomaucho.servergui;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny read-only SQLite reader: just enough to read every row of one table.
 * Used to find the skins saved in the Modrinth App (it keeps them in a SQLite database).
 * Also reads the "-wal" file so very recently saved skins are included.
 */
final class SqliteReader implements AutoCloseable {
    private final RandomAccessFile file;
    private final int pageSize;
    private final int usable;
    private final Map<Integer, byte[]> walPages = new HashMap<>();

    SqliteReader(Path db) throws IOException {
        file = new RandomAccessFile(db.toFile(), "r");
        byte[] header = new byte[100];
        file.readFully(header);
        if (!new String(header, 0, 15).equals("SQLite format 3")) throw new IOException("not a SQLite file");
        int ps = ((header[16] & 0xFF) << 8) | (header[17] & 0xFF);
        pageSize = ps == 1 ? 65536 : ps;
        usable = pageSize - (header[20] & 0xFF);
        readWal(Path.of(db + "-wal"));
    }

    /** Newest copy of each page from the write-ahead log (only frames from the current log run). */
    private void readWal(Path wal) {
        try {
            if (!Files.exists(wal)) return;
            byte[] w = Files.readAllBytes(wal);
            if (w.length < 32) return;
            int walPageSize = readInt(w, 8);
            if (walPageSize != pageSize) return;
            int salt1 = readInt(w, 16), salt2 = readInt(w, 20);
            Map<Integer, byte[]> pending = new HashMap<>();
            for (int off = 32; off + 24 + pageSize <= w.length; off += 24 + pageSize) {
                if (readInt(w, off + 8) != salt1 || readInt(w, off + 12) != salt2) break;
                int pageNo = readInt(w, off);
                byte[] page = new byte[pageSize];
                System.arraycopy(w, off + 24, page, 0, pageSize);
                pending.put(pageNo, page);
                if (readInt(w, off + 4) != 0) {           // commit frame: everything so far is saved
                    walPages.putAll(pending);
                    pending.clear();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private byte[] page(int n) throws IOException {
        byte[] fromWal = walPages.get(n);
        if (fromWal != null) return fromWal;
        byte[] p = new byte[pageSize];
        file.seek((long) (n - 1) * pageSize);
        file.readFully(p);
        return p;
    }

    /** All rows of a table, each as a list of column values (String, Long, Double, byte[] or null). */
    List<List<Object>> readTable(String table) throws IOException {
        for (List<Object> row : rows(1)) {
            // sqlite_master: type, name, tbl_name, rootpage, sql
            if (row.size() >= 4 && "table".equals(row.get(0)) && table.equals(row.get(1)) && row.get(3) instanceof Long root) {
                return rows(root.intValue());
            }
        }
        return List.of();
    }

    private List<List<Object>> rows(int root) throws IOException {
        List<List<Object>> out = new ArrayList<>();
        walk(root, out, 0);
        return out;
    }

    private void walk(int pageNo, List<List<Object>> out, int depth) throws IOException {
        if (depth > 40) return;
        byte[] p = page(pageNo);
        int h = pageNo == 1 ? 100 : 0;
        int type = p[h] & 0xFF;
        int cells = readShort(p, h + 3);
        if (type == 0x05) {                                   // interior table page
            for (int i = 0; i < cells; i++) {
                int cell = readShort(p, h + 12 + i * 2);
                walk(readInt(p, cell), out, depth + 1);
            }
            walk(readInt(p, h + 8), out, depth + 1);
        } else if (type == 0x0D) {                            // leaf table page
            for (int i = 0; i < cells; i++) {
                int cell = readShort(p, h + 8 + i * 2);
                long[] v = varint(p, cell);
                long payloadSize = v[0];
                int pos = cell + (int) v[1];
                v = varint(p, pos);                           // rowid
                pos += (int) v[1];
                out.add(record(payload(p, pos, (int) payloadSize)));
            }
        }
    }

    /** The cell's whole payload, following overflow pages when it doesn't fit on one page. */
    private byte[] payload(byte[] p, int pos, int size) throws IOException {
        int x = usable - 35;
        if (size <= x) {
            byte[] b = new byte[size];
            System.arraycopy(p, pos, b, 0, size);
            return b;
        }
        int m = ((usable - 12) * 32 / 255) - 23;
        int k = m + ((size - m) % (usable - 4));
        int local = k <= x ? k : m;
        byte[] b = new byte[size];
        System.arraycopy(p, pos, b, 0, local);
        int done = local;
        int next = readInt(p, pos + local);
        int guard = 0;
        while (done < size && next != 0 && guard++ < 100000) {
            byte[] o = page(next);
            int n = Math.min(usable - 4, size - done);
            System.arraycopy(o, 4, b, done, n);
            done += n;
            next = readInt(o, 0);
        }
        return b;
    }

    private static List<Object> record(byte[] r) {
        List<Object> values = new ArrayList<>();
        long[] v = varint(r, 0);
        int headerSize = (int) v[0];
        int hp = (int) v[1];
        List<Long> types = new ArrayList<>();
        while (hp < headerSize) {
            v = varint(r, hp);
            types.add(v[0]);
            hp += (int) v[1];
        }
        int dp = headerSize;
        for (long t : types) {
            if (t == 0) values.add(null);
            else if (t >= 1 && t <= 6) {
                int len = t == 5 ? 6 : t == 6 ? 8 : (int) t;
                long val = r[dp];                              // sign-extended first byte
                for (int i = 1; i < len; i++) val = (val << 8) | (r[dp + i] & 0xFF);
                values.add(val);
                dp += len;
            } else if (t == 7) {
                long bits = 0;
                for (int i = 0; i < 8; i++) bits = (bits << 8) | (r[dp + i] & 0xFF);
                values.add(Double.longBitsToDouble(bits));
                dp += 8;
            } else if (t == 8) values.add(0L);
            else if (t == 9) values.add(1L);
            else if (t >= 12 && t % 2 == 0) {
                int len = (int) ((t - 12) / 2);
                byte[] b = new byte[len];
                System.arraycopy(r, dp, b, 0, len);
                values.add(b);
                dp += len;
            } else if (t >= 13) {
                int len = (int) ((t - 13) / 2);
                values.add(new String(r, dp, len, java.nio.charset.StandardCharsets.UTF_8));
                dp += len;
            } else values.add(null);
        }
        return values;
    }

    /** Returns {value, bytesUsed}. */
    private static long[] varint(byte[] b, int pos) {
        long v = 0;
        for (int i = 0; i < 9; i++) {
            int c = b[pos + i] & 0xFF;
            if (i == 8) return new long[]{(v << 8) | c, 9};
            v = (v << 7) | (c & 0x7F);
            if ((c & 0x80) == 0) return new long[]{v, i + 1};
        }
        return new long[]{v, 9};
    }

    private static int readShort(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int pos) {
        return ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16) | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
