package com.glasstv.status;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Runs a shell command through the TV's own debugging service (the one the setup used over the network),
 * the same way Glass TV clears caches: Android shows no app other apps' memory and lets none stop another
 * app, but the debugging shell can. The first time, the TV shows "Allow USB debugging?" once; with
 * "Always allow" ticked it does not ask again. Only used for the Apps tab: listing processes and
 * stopping an app the owner picked.
 */
final class TvShell {
    private static final int PORT = 5555;

    /** Not allowed in: the prompt was declined, not answered, or not shown ({@code mayAsk} false). */
    static final class NotAllowed extends Exception {
    }

    private TvShell() {
    }

    /**
     * Runs {@code command} and returns what it printed. IOException when the debugging service is off or
     * unreachable; NotAllowed when the TV did not let this app in.
     */
    static String run(Context ctx, boolean mayAsk, String command) throws Exception {
        KeyPair keys = keys(ctx);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            socket.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            send(out, CNXN, 0x01000000, 256 * 1024, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Message m = read(in);
            if (m.cmd == AUTH && m.arg0 == AUTH_TOKEN) {
                send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                m = read(in);
                if (m.cmd == AUTH && m.arg0 == AUTH_TOKEN) {
                    if (!mayAsk) throw new NotAllowed();
                    // Not trusted yet: the TV now asks "Allow USB debugging?". Wait for the answer.
                    send(out, AUTH, AUTH_PUBLIC_KEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                    socket.setSoTimeout(60_000);
                    try {
                        m = read(in);
                    } catch (SocketTimeoutException | EOFException e) {
                        throw new NotAllowed();
                    }
                }
            }
            if (m.cmd != CNXN) throw new NotAllowed();

            socket.setSoTimeout(30_000);
            send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            while (true) {
                m = read(in);
                if (m.cmd == WRTE) {
                    text.write(m.data);
                    send(out, OKAY, 1, m.arg0, null);
                } else if (m.cmd == CLSE) {
                    send(out, CLSE, 1, m.arg0, null);
                    return text.toString("UTF-8");
                }
            }
        }
    }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_PUBLIC_KEY = 3;
    /** PKCS#1 DigestInfo for SHA-1: the service signs its 20-byte token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    private static final class Message {
        int cmd, arg0;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1 << 20) throw new IOException("bad message length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(key);
        s.update(SHA1_PREFIX);
        s.update(token);
        return s.sign();
    }

    /** The public key in the service's own format: a 2048-bit RSA key as little-endian words, base64. */
    private static byte[] publicKey(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        for (int i = 0; i < 64; i++) b.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < 64; i++) b.putInt(rr.shiftRight(32 * i).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " TV Status\0";
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** This app's own key, made once and kept in its private files. */
    private static KeyPair keys(Context ctx) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            try {
                return new KeyPair(f.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        f.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            } catch (Exception e) {
                // Unreadable: make a new one (the TV asks once more).
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair k = g.generateKeyPair();
        writeAll(priv, k.getPrivate().getEncoded());
        writeAll(pub, k.getPublic().getEncoded());
        return k;
    }

    private static byte[] readAll(File file) throws IOException {
        byte[] buf = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        }
        return buf;
    }

    private static void writeAll(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }
}
