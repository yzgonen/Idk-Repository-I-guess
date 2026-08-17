package com.vanguard.image2schem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

final class SchemWriterTest {
    @TempDir Path temp;

    @Test
    void writesReadableSpongeV3StructureAndExactBlockCount() throws Exception {
        Map<String,Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        palette.put("minecraft:stone_bricks", 1);
        int w=5,h=4,d=3;
        int[] ids = new int[w*h*d];
        for (int i=0;i<ids.length;i++) ids[i]=(i%4==0)?1:0;
        ImageConverter.Result result = new ImageConverter.Result(w,h,d,ids,palette);
        Path file=temp.resolve("qa.schem");

        SchemWriter.write(file,result,"QA Scene");
        assertTrue(Files.size(file)>50);

        Map<String,Object> root=readRoot(file);
        assertTrue(root.containsKey("Schematic"));
        Map<String,Object> schematic=compound(root,"Schematic");
        assertEquals(3, schematic.get("Version"));
        assertEquals((short)w, schematic.get("Width"));
        assertEquals((short)h, schematic.get("Height"));
        assertEquals((short)d, schematic.get("Length"));
        assertTrue(schematic.containsKey("DataVersion"));

        Map<String,Object> metadata=compound(schematic,"Metadata");
        assertEquals("QA Scene", metadata.get("Name"));
        assertEquals("Image2Schem", metadata.get("Author"));

        Map<String,Object> blocks=compound(schematic,"Blocks");
        Map<String,Object> parsedPalette=compound(blocks,"Palette");
        assertEquals(0, parsedPalette.get("minecraft:air"));
        assertEquals(1, parsedPalette.get("minecraft:stone_bricks"));
        byte[] data=(byte[])blocks.get("Data");
        assertNotNull(data);
        assertEquals(ids.length, decodeVarIntCount(data));
        assertTrue(blocks.containsKey("BlockEntities"));
    }

    private static Map<String,Object> readRoot(Path file)throws IOException{
        try(InputStream raw=Files.newInputStream(file); GZIPInputStream gzip=new GZIPInputStream(raw); DataInputStream in=new DataInputStream(gzip)){
            int type=in.readUnsignedByte();
            assertEquals(10,type,"root must be TAG_Compound");
            readUtf(in); // root name
            return readCompoundPayload(in);
        }
    }

    private static Map<String,Object> readCompoundPayload(DataInputStream in)throws IOException{
        Map<String,Object> out=new LinkedHashMap<>();
        while(true){
            int type=in.readUnsignedByte();
            if(type==0)return out;
            String name=readUtf(in);
            out.put(name,readPayload(in,type));
        }
    }

    private static Object readPayload(DataInputStream in,int type)throws IOException{
        return switch(type){
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 7 -> { int n=in.readInt(); byte[] b=in.readNBytes(n); if(b.length!=n)throw new IOException("short byte array"); yield b; }
            case 8 -> readUtf(in);
            case 9 -> {
                int child=in.readUnsignedByte(),n=in.readInt();
                java.util.ArrayList<Object> list=new java.util.ArrayList<>(n);
                for(int i=0;i<n;i++)list.add(readPayload(in,child));
                yield List.copyOf(list);
            }
            case 10 -> readCompoundPayload(in);
            case 11 -> { int n=in.readInt(); int[] a=new int[n]; for(int i=0;i<n;i++)a[i]=in.readInt(); yield a; }
            default -> throw new IOException("unsupported test NBT tag "+type);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> compound(Map<String,Object> parent,String name){
        Object value=parent.get(name);
        assertInstanceOf(Map.class,value,"missing compound "+name);
        return (Map<String,Object>)value;
    }

    private static String readUtf(DataInputStream in)throws IOException{
        int n=in.readUnsignedShort();
        return new String(in.readNBytes(n), StandardCharsets.UTF_8);
    }

    private static int decodeVarIntCount(byte[] data)throws IOException{
        int count=0,i=0;
        while(i<data.length){
            int shift=0;
            while(true){
                if(i>=data.length)throw new IOException("truncated varint");
                int b=data[i++]&255;
                shift+=7;
                if((b&0x80)==0)break;
                if(shift>35)throw new IOException("oversized varint");
            }
            count++;
        }
        return count;
    }
}
