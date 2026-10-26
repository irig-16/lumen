package com.lumen.launcher;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Small QR code encoder (byte mode, error correction M, versions 1–40), after the structure of
 * Project Nayuki's MIT-licensed reference implementation. Pure Java: used to draw the "scan to
 * connect" code for the phone remote.
 */
final class QrCode {

    final int size;
    final boolean[][] modules;   // [y][x], true = dark
    private final boolean[][] isFunction;
    private final int version;

    private static final int ECL = 1; // index into the tables below: 0 L, 1 M, 2 Q, 3 H
    private static final int ECL_FORMAT_BITS = 0; // M

    private static final byte[][] ECC_CODEWORDS_PER_BLOCK = {
            {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
            {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30}};

    private static final byte[][] NUM_ERROR_CORRECTION_BLOCKS = {
            {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
            {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
            {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},
            {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81}};

    /** Encodes text (UTF-8). */
    static QrCode encode(String text) {
        byte[] data = text.getBytes(Charset.forName("UTF-8"));
        int ver;
        int ccBits = 8;
        for (ver = 1; ; ver++) {
            ccBits = ver <= 9 ? 8 : 16;
            int needed = 4 + ccBits + data.length * 8;
            if (needed <= dataCodewords(ver) * 8) break;
            if (ver >= 40) throw new IllegalArgumentException("text too long for a QR code");
        }
        // bit stream: byte mode, count, data, terminator, padding
        BitBuffer bb = new BitBuffer();
        bb.append(4, 4);
        bb.append(data.length, ccBits);
        for (byte b : data) bb.append(b & 0xFF, 8);
        int capacity = dataCodewords(ver) * 8;
        bb.append(0, Math.min(4, capacity - bb.len));
        bb.append(0, (8 - bb.len % 8) % 8);
        for (int pad = 0xEC; bb.len < capacity; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] codewords = new byte[bb.len / 8];
        for (int i = 0; i < bb.len; i++) if (bb.get(i)) codewords[i >>> 3] |= 1 << (7 - (i & 7));
        return new QrCode(ver, codewords);
    }

    private QrCode(int ver, byte[] dataCodewords) {
        version = ver;
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
        drawFunctionPatterns();
        drawCodewords(addEccAndInterleave(dataCodewords));
        int best = 0;
        long minPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            long p = penalty();
            if (p < minPenalty) {
                minPenalty = p;
                best = m;
            }
            applyMask(m); // undo (XOR)
        }
        applyMask(best);
        drawFormatBits(best);
    }

    // ------------------------------------------------------------- function patterns

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFn(6, i, i % 2 == 0);
            setFn(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] pos = alignmentPositions();
        int n = pos.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) drawAlignment(pos[i], pos[j]);
            }
        }
        drawFormatBits(0);
        drawVersion();
    }

    private void drawFormatBits(int mask) {
        int data = ECL_FORMAT_BITS << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFn(8, i, bit(bits, i));
        setFn(8, 7, bit(bits, 6));
        setFn(8, 8, bit(bits, 7));
        setFn(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFn(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFn(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFn(8, size - 15 + i, bit(bits, i));
        setFn(8, size - 8, true);
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        long bits = (long) version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = ((bits >>> i) & 1) != 0;
            int a = size - 11 + i % 3, c = i / 3;
            setFn(a, c, b);
            setFn(c, a, b);
        }
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setFn(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++) setFn(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int n = version / 7 + 2;
        int step = (version * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, pos = size - 7; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void setFn(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    // ------------------------------------------------------------- codewords

    private static int rawDataModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int n = ver / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int ver) {
        return rawDataModules(ver) / 8 - ECC_CODEWORDS_PER_BLOCK[ECL][ver] * NUM_ERROR_CORRECTION_BLOCKS[ECL][ver];
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ECL][version];
        int eccLen = ECC_CODEWORDS_PER_BLOCK[ECL][version];
        int raw = rawDataModules(version) / 8;
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;
        byte[][] blocks = new byte[numBlocks][];
        byte[] div = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            byte[] dat = Arrays.copyOfRange(data, k, k + shortLen - eccLen + (i < numShort ? 0 : 1));
            k += dat.length;
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            byte[] ecc = rsRemainder(dat, div);
            System.arraycopy(ecc, 0, block, block.length - eccLen, ecc.length);
            blocks[i] = block;
        }
        byte[] result = new byte[raw];
        for (int i = 0, k = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < blocks.length; j++) {
                if (i != shortLen - eccLen || j >= numShort) result[k++] = blocks[j][i];
            }
        }
        return result;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv && !isFunction[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** Standard QR penalty rules: long runs, 2x2 blocks, finder look-alikes, dark/light balance. */
    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? modules[a][b] : modules[b][a];
                    boolean prev = pass == 0 ? modules[a][b - 1] : modules[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) p += 3;
                        else if (run > 5) p++;
                    } else {
                        run = 1;
                    }
                }
                for (int b = -4; b + 7 <= size; b++) {
                    if (finderLike(pass, a, b)) p += 40;
                }
            }
        }
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
            }
        int dark = 0;
        for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        p += Math.max(0, k) * 10L;
        return p;
    }

    private static final boolean[] FINDER_A = {false, false, false, false, true, false, true, true, true, false, true};
    private static final boolean[] FINDER_B = {true, false, true, true, true, false, true, false, false, false, false};

    /** 1:1:3:1:1 dark/light run with four light modules on one side (outside the code counts as light). */
    private boolean finderLike(int pass, int a, int start) {
        boolean ma = true, mb = true;
        for (int i = 0; i < 11; i++) {
            int b = start + i;
            boolean v = b >= 0 && b < size && (pass == 0 ? modules[a][b] : modules[b][a]);
            if (v != FINDER_A[i]) ma = false;
            if (v != FINDER_B[i]) mb = false;
        }
        return ma || mb;
    }

    // ------------------------------------------------------------- GF(256) Reed–Solomon

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xFF, factor);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static boolean bit(long x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class BitBuffer {
        boolean[] bits = new boolean[64];
        int len;

        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--) {
                if (len == bits.length) bits = Arrays.copyOf(bits, len * 2);
                bits[len++] = ((val >>> i) & 1) != 0;
            }
        }

        boolean get(int i) {
            return bits[i];
        }
    }
}
