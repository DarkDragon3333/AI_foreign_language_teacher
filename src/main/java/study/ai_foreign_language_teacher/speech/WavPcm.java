package study.ai_foreign_language_teacher.speech;

/** Обёртка PCM 16-bit mono в минимальный WAV для сервиса распознавания. */
public final class WavPcm {

    private WavPcm() {
    }

    public static byte[] wrap(byte[] pcm16le, int sampleRate) {
        if (pcm16le == null) {
            pcm16le = new byte[0];
        }
        int dataLen = pcm16le.length;
        int total = 44 + dataLen;
        byte[] out = new byte[total];
        out[0] = 'R';
        out[1] = 'I';
        out[2] = 'F';
        out[3] = 'F';
        writeInt(out, 4, total - 8);
        out[8] = 'W';
        out[9] = 'A';
        out[10] = 'V';
        out[11] = 'E';
        out[12] = 'f';
        out[13] = 'm';
        out[14] = 't';
        out[15] = ' ';
        writeInt(out, 16, 16);
        writeShort(out, 20, (short) 1);
        writeShort(out, 22, (short) 1);
        writeInt(out, 24, sampleRate);
        int byteRate = sampleRate * 2;
        writeInt(out, 28, byteRate);
        writeShort(out, 32, (short) 2);
        writeShort(out, 34, (short) 16);
        out[36] = 'd';
        out[37] = 'a';
        out[38] = 't';
        out[39] = 'a';
        writeInt(out, 40, dataLen);
        System.arraycopy(pcm16le, 0, out, 44, dataLen);
        return out;
    }

    private static void writeInt(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >> 8) & 0xff);
        out[offset + 2] = (byte) ((value >> 16) & 0xff);
        out[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void writeShort(byte[] out, int offset, short value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >> 8) & 0xff);
    }
}