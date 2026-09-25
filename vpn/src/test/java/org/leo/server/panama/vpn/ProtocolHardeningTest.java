package org.leo.server.panama.vpn;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import org.leo.server.panama.vpn.reverse.protocol.ReverseProtocol;
import org.leo.server.panama.vpn.security.wrapper.*;
import org.leo.server.panama.vpn.configuration.*;
import org.leo.server.panama.vpn.util.FileUtils;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class ProtocolHardeningTest {
    private byte[] bytes(ByteBuf buffer) {
        try { byte[] result = new byte[buffer.readableBytes()]; buffer.readBytes(result); return result; }
        finally { buffer.release(); }
    }
    @Test public void reverseHeadersAndBodiesSurviveEverySplitAndCoalescing() {
        byte[] first = bytes(ReverseProtocol.encodeProtocol(123, new byte[]{1, 2, 3}));
        byte[] second = bytes(ReverseProtocol.encodeProtocol(456, new byte[]{4, 5}));
        byte[] all = bytes(Unpooled.wrappedBuffer(first, second));
        for (int split = 0; split <= all.length; split++) {
            ReverseProtocol.Decoder decoder = new ReverseProtocol.Decoder();
            List<ReverseProtocol.ReverseProtocolData> result = new ArrayList<>();
            result.addAll(decoder.feed(Arrays.copyOfRange(all, 0, split)));
            result.addAll(decoder.feed(Arrays.copyOfRange(all, split, all.length)));
            assertEquals(2, result.size());
            assertEquals(123, result.get(0).getTag()); assertArrayEquals(new byte[]{1, 2, 3}, result.get(0).getData());
            assertEquals(456, result.get(1).getTag()); assertArrayEquals(new byte[]{4, 5}, result.get(1).getData());
        }
    }
    @Test public void reverseRejectsInvalidLengthBeforeAllocationAndResetDropsOldPartialData() {
        ReverseProtocol.Decoder decoder = new ReverseProtocol.Decoder();
        byte[] oversized = bytes(Unpooled.buffer().writeInt(1).writeInt(ReverseProtocol.MAX_FRAME_LENGTH + 1));
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(oversized));
        decoder.reset();
        byte[] negative = bytes(Unpooled.buffer().writeInt(1).writeInt(-1));
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(negative));
        decoder.reset(); decoder.feed(new byte[]{0, 0}); decoder.reset();
        assertEquals(9, decoder.feed(bytes(ReverseProtocol.encodeProtocol(9, new byte[0]))).get(0).getTag());
    }
    @Test public void legacyDecoderApiAlsoAcceptsSplitHeaders() {
        byte[] encoded = bytes(ReverseProtocol.encodeProtocol(42, new byte[]{3, 4}));
        ReverseProtocol.ReverseProtocolData partial = null;
        for (byte b : encoded) {
            List<ReverseProtocol.ReverseProtocolData> frames = ReverseProtocol.decodeProtocol(new byte[]{b}, partial);
            assertEquals(1, frames.size()); partial = frames.get(0);
        }
        assertTrue(partial.isComplete()); assertArrayEquals(new byte[]{3, 4}, partial.getData());
    }
    @Test public void framingRetainsContinuationDataAcrossReadsAndCoalescedPackets() {
        byte[] a = new byte[100]; for (int i=0;i<a.length;i++) a[i]=(byte)i;
        for (int size : new int[]{4, 16, 256, 262144}) {
            FrameWrapper encoder = new FrameWrapper(size);
            byte[] wire = bytes(Unpooled.wrappedBuffer(encoder.wrap(a), encoder.wrap(new byte[]{9,8})));
            FrameWrapper decoder = new FrameWrapper(size);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (byte b : wire) {
                byte[] read = decoder.unwrap(new byte[]{b}); assertNotNull(read); output.write(read, 0, read.length);
            }
            assertArrayEquals(bytes(Unpooled.wrappedBuffer(a,new byte[]{9,8})), output.toByteArray());
        }
    }
    @Test(timeout=2000) public void compressionHandlesEmptyAndTruncatedDataWithoutSpinning() {
        CompressWrapper codec = new CompressWrapper();
        assertArrayEquals(new byte[0], codec.unwrap(codec.wrap(new byte[0])));
        byte[] compressed = codec.wrap(new byte[10000]);
        assertThrows(IllegalArgumentException.class, () -> codec.unwrap(Arrays.copyOf(compressed, compressed.length-1)));
        assertThrows(IllegalArgumentException.class, () -> codec.unwrap(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> codec.unwrap(new byte[]{1,2,3}));
        assertArrayEquals(new byte[]{3,4},codec.unwrap(codec.wrap(new byte[]{3,4})));
    }
    @Test public void allFrameHandlersSupportFragmentedLargeMessages() {
        byte[] payload = new byte[600000]; new Random(1).nextBytes(payload);
        for (String kind : new String[]{"compress","zero-padding","random-padding"}) {
            Wrapper encoder = WrapperFactory.getInstance(null,null,kind);
            Wrapper decoder = WrapperFactory.getInstance(null,null,kind);
            byte[] wire=encoder.wrap(payload);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            for(int pos=0;pos<wire.length;pos+=137) {
                byte[] read=decoder.unwrap(Arrays.copyOfRange(wire,pos,Math.min(pos+137,wire.length)));
                output.write(read,0,read.length);
            }
            assertArrayEquals(kind,payload,output.toByteArray());
        }
    }
    @Test public void everySupportedCipherAcceptsBytewiseIvAndPayload() {
        byte[] plain = new byte[1024]; new Random(3).nextBytes(plain);
        for (String type : new String[]{"aes-128-cfb", "aes-192-cfb", "aes-256-cfb", "aes-128-ofb", "aes-192-ofb", "aes-256-ofb", "bf-cfb"}) {
            Wrapper encoder = WrapperFactory.getInstance(type, "test", "encrypt");
            Wrapper decoder = WrapperFactory.getInstance(type, "test", "encrypt");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (byte value : encoder.wrap(plain)) {
                byte[] decoded = decoder.unwrap(new byte[]{value}); output.write(decoded, 0, decoded.length);
            }
            assertArrayEquals(type, plain, output.toByteArray());
        }
    }
    @Test public void malformedPaddingAndFrameHeadersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FrameWrapper(16).unwrap(new byte[]{2}));
        assertThrows(IllegalArgumentException.class, () -> new FrameWrapper(16).unwrap(new byte[]{0,16}));
        assertThrows(IllegalArgumentException.class, () -> new ZeroPaddingWrapper(200,56).unwrap(new byte[]{0,0}));
        assertThrows(IllegalArgumentException.class, () -> new RandomPaddingWrapper(200,56).unwrap(new byte[]{0,99}));
    }
    @Test public void absoluteConfigurationPathWorksAndMissingOrEmptyFilesFailClosed() throws Exception {
        Path config=Files.createTempFile("panama-config-", ".json");
        try {
            Files.write(config,"{\"port\":12345,\"password\":\"test\"}".getBytes(StandardCharsets.UTF_8));
            assertEquals(12345,ConfigurationReader.read(config.toString()).getPort());
            Files.write(config,new byte[0]);
            assertThrows(IllegalArgumentException.class,()->ConfigurationReader.read(config.toString()));
            Path missing=config.resolveSibling("missing-"+UUID.randomUUID());
            assertThrows(IllegalArgumentException.class,()->FileUtils.read(missing.toString(),"default"));
            assertFalse(Files.exists(missing));
        } finally { Files.deleteIfExists(config); }
    }
    @Test public void validatesModeEndpointsAndEffectiveProxyCipherDefaults() {
        ShadowSocksConfiguration config=new ShadowSocksConfiguration();
        config.setMode("typo"); assertThrows(IllegalArgumentException.class,config::validate);
        config.setMode("proxy"); assertThrows(IllegalArgumentException.class,config::validate);
        config.setProxy("localhost"); config.setProxyPort(12345); config.setProxyType("aes-256-cfb");config.setProxyPassword("123456");
        assertTrue(config.isProxyEqualsCurrent()); config.validate();
        config.setProxyPort(-1); assertThrows(IllegalArgumentException.class,config::validate);
    }
}
