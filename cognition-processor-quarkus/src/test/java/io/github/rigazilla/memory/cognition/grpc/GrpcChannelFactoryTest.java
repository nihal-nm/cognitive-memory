package io.github.rigazilla.memory.cognition.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

class GrpcChannelFactoryTest {

    private static final MethodDescriptor.Marshaller<byte[]> BYTE_MARSHALLER =
            new MethodDescriptor.Marshaller<>() {
                @Override
                public InputStream stream(byte[] value) {
                    return new ByteArrayInputStream(value);
                }

                @Override
                public byte[] parse(InputStream stream) {
                    return new byte[0];
                }
            };

    private static final MethodDescriptor<byte[], byte[]> METHOD = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Service/Call")
            .setRequestMarshaller(BYTE_MARSHALLER)
            .setResponseMarshaller(BYTE_MARSHALLER)
            .build();

    @Test
    void addsApiKeyAndClientIdHeaders() {
        CapturingChannel channel = new CapturingChannel();
        ClientCall<byte[], byte[]> call = new GrpcChannelFactory.AuthInterceptor("secret", "worker")
                .interceptCall(METHOD, CallOptions.DEFAULT, channel);

        call.start(new NoopListener<>(), new Metadata());

        assertEquals("secret", channel.headers.get(GrpcChannelFactory.API_KEY_HEADER));
        assertEquals("worker", channel.headers.get(GrpcChannelFactory.CLIENT_ID_HEADER));
    }

    @Test
    void omitsClientIdHeaderWhenNotConfigured() {
        CapturingChannel channel = new CapturingChannel();
        ClientCall<byte[], byte[]> call = new GrpcChannelFactory.AuthInterceptor("secret", null)
                .interceptCall(METHOD, CallOptions.DEFAULT, channel);

        call.start(new NoopListener<>(), new Metadata());

        assertEquals("secret", channel.headers.get(GrpcChannelFactory.API_KEY_HEADER));
        assertNull(channel.headers.get(GrpcChannelFactory.CLIENT_ID_HEADER));
    }

    private static final class CapturingChannel extends Channel {
        private Metadata headers;

        @Override
        public String authority() {
            return "test";
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
            return new ClientCall<>() {
                @Override
                public void start(Listener<RespT> responseListener, Metadata metadata) {
                    headers = metadata;
                }

                @Override
                public void request(int numMessages) {
                }

                @Override
                public void cancel(String message, Throwable cause) {
                }

                @Override
                public void halfClose() {
                }

                @Override
                public void sendMessage(ReqT message) {
                }
            };
        }
    }

    private static final class NoopListener<T> extends ClientCall.Listener<T> {
    }
}
