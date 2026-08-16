package com.vanguard.image2schem;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public final class SchemWriter {
    private static final int TAG_END = 0;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;

    private SchemWriter() {}

    public static void write(Path output, ImageConverter.Result result, String name) throws IOException {
        Files.createDirectories(output.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(output))))) {
            out.writeByte(TAG_COMPOUND);
            writeUtf(out, "");

            out.writeByte(TAG_COMPOUND);
            writeUtf(out, "Schematic");

            writeInt(out, "Version", 3);
            writeInt(out, "DataVersion", 0);
            writeShort(out, "Width", result.width());
            writeShort(out, "Height", result.height());
            writeShort(out, "Length", result.length());
            writeIntArray(out, "Offset", new int[]{0, 0, 0});

            out.writeByte(TAG_COMPOUND);
            writeUtf(out, "Metadata");
            writeString(out, "Name", name);
            writeString(out, "Author", "Image2Schem");
            out.writeByte(TAG_END);

            out.writeByte(TAG_COMPOUND);
            writeUtf(out, "Blocks");

            out.writeByte(TAG_COMPOUND);
            writeUtf(out, "Palette");
            for (Map.Entry<String, Integer> e : result.palette().entrySet()) {
                writeInt(out, e.getKey(), e.getValue());
            }
            out.writeByte(TAG_END);

            writeByteArray(out, "Data", encodeVarInts(result.paletteIds()));

            out.writeByte(TAG_LIST);
            writeUtf(out, "BlockEntities");
            out.writeByte(TAG_COMPOUND);
            out.writeInt(0);

            out.writeByte(TAG_END); // Blocks
            out.writeByte(TAG_END); // Schematic
            out.writeByte(TAG_END); // root
        }
    }

    private static byte[] encodeVarInts(int[] values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(values.length);
        for (int value : values) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                bytes.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            bytes.write(v);
        }
        return bytes.toByteArray();
    }

    private static void writeShort(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(TAG_SHORT); writeUtf(out, name); out.writeShort(value);
    }
    private static void writeInt(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(TAG_INT); writeUtf(out, name); out.writeInt(value);
    }
    private static void writeString(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(TAG_STRING); writeUtf(out, name); writeUtf(out, value);
    }
    private static void writeByteArray(DataOutputStream out, String name, byte[] value) throws IOException {
        out.writeByte(TAG_BYTE_ARRAY); writeUtf(out, name); out.writeInt(value.length); out.write(value);
    }
    private static void writeIntArray(DataOutputStream out, String name, int[] value) throws IOException {
        out.writeByte(TAG_INT_ARRAY); writeUtf(out, name); out.writeInt(value.length); for (int v : value) out.writeInt(v);
    }
    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length); out.write(bytes);
    }
}
