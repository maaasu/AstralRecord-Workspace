using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

// These small packs use ZIP method 0 (store). .NET Framework emits
// deflate for NoCompression while modern .NET uses store, so write the
// fixed ZIP headers here to keep the output identical across runtimes.
public static class AstralRecordPackZip
{
    public static void Write(string path, SortedDictionary<string, byte[]> entries)
    {
        using (var stream = File.Create(path))
        using (var zip = new BinaryWriter(stream))
        using (var directory = new MemoryStream())
        using (var central = new BinaryWriter(directory))
        {
            foreach (var entry in entries)
            {
                var name = Encoding.UTF8.GetBytes(entry.Key);
                var data = entry.Value;
                var offset = checked((uint)stream.Position);
                var crc = Crc32(data);

                zip.Write(0x04034b50u);
                WriteEntry(zip, name, data, crc);
                zip.Write(name);
                zip.Write(data);

                central.Write(0x02014b50u);
                central.Write((ushort)20); // ZIP version made by, MS-DOS platform
                WriteEntry(central, name, data, crc);
                central.Write((ushort)0); // comment length
                central.Write((ushort)0); // disk number
                central.Write((ushort)0); // internal attributes
                central.Write(0u); // external attributes
                central.Write(offset);
                central.Write(name);
            }

            var directoryOffset = checked((uint)stream.Position);
            zip.Write(directory.ToArray());
            zip.Write(0x06054b50u);
            zip.Write(0u); // disk number and central directory disk
            zip.Write(checked((ushort)entries.Count));
            zip.Write(checked((ushort)entries.Count));
            zip.Write(checked((uint)directory.Length));
            zip.Write(directoryOffset);
            zip.Write((ushort)0); // archive comment length
        }
    }

    private static void WriteEntry(BinaryWriter writer, byte[] name, byte[] data, uint crc)
    {
        writer.Write((ushort)20); // ZIP version needed
        writer.Write((ushort)0x0800); // UTF-8 file names
        writer.Write((ushort)0); // stored without compression
        writer.Write((ushort)0); // DOS time
        writer.Write((ushort)0x0021); // DOS date: 1980-01-01
        writer.Write(crc);
        writer.Write((uint)data.Length);
        writer.Write((uint)data.Length);
        writer.Write(checked((ushort)name.Length));
        writer.Write((ushort)0); // extra field length
    }

    private static uint Crc32(byte[] bytes)
    {
        uint crc = 0xffffffff;
        foreach (byte value in bytes)
        {
            crc ^= value;
            for (int bit = 0; bit < 8; bit++)
                crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb88320u : 0u);
        }
        return ~crc;
    }
}
