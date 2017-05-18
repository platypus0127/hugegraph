package com.baidu.hugegraph.api.filter;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

import com.baidu.hugegraph.HugeException;

public class ExceptionFilter {

    @Provider
    public static class HugeExceptionMapper
            implements ExceptionMapper<HugeException> {

        @Override
        public Response toResponse(HugeException exception) {
            return Response.status(400)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(formatException(exception))
                    .build();
        }
    }

    @Provider
    public static class IllegalArgumentExceptionMapper
            implements ExceptionMapper<IllegalArgumentException> {

        @Override
        public Response toResponse(IllegalArgumentException exception) {
            return Response.status(400)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(formatException(exception))
                    .build();
        }
    }

    @Provider
    public static class NotFoundExceptionExceptionMapper
            implements ExceptionMapper<NotFoundException> {

        @Override
        public Response toResponse(NotFoundException exception) {
            return Response.status(404)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(formatException(exception))
                    .build();
        }
    }

    @Provider
    public static class UnknownExceptionMapper
            implements ExceptionMapper<Exception> {

        @Override
        public Response toResponse(Exception exception) {
            return Response.status(500)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(formatException(exception, true))
                    .build();
        }
    }

    public static String formatException(Exception exception) {
        return formatException(exception, false);
    }

    public static String formatException(Exception exception, boolean trace) {
        String msg = (exception.getMessage() != null
                ? exception.getMessage() : "");
        String cause = (exception.getCause() != null
                ? exception.getCause().toString() : "");

        JsonObjectBuilder json = Json.createObjectBuilder()
                .add("exception", exception.getClass().toString())
                .add("message", msg)
                .add("cause", cause);

        if (trace) {
            JsonArrayBuilder traces = Json.createArrayBuilder();
            for (StackTraceElement i : exception.getStackTrace()) {
                traces.add(i.toString());
            }
            json.add("trace", traces);
        }

        return json.build().toString();
    }
}