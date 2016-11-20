/**************************************************
 * Android Web Server
 * Based on JavaLittleWebServer (2008)
 * <p/>
 * Copyright (c) Piotr Polak 2016-2016
 **************************************************/

package ro.polak.http.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ro.polak.http.Headers;
import ro.polak.http.MultipartRequestHandler;
import ro.polak.http.RequestStatus;
import ro.polak.http.Statistics;
import ro.polak.http.protocol.exception.MalformedOrUnsupporedMethodProtocolException;
import ro.polak.http.protocol.exception.StatusLineTooLongProtocolException;
import ro.polak.http.protocol.exception.UriTooLongProtocolException;
import ro.polak.http.protocol.parser.Parser;
import ro.polak.http.protocol.parser.impl.CookieParser;
import ro.polak.http.protocol.parser.impl.HeadersParser;
import ro.polak.http.protocol.parser.impl.QueryStringParser;
import ro.polak.http.protocol.parser.impl.RequestStatusParser;

/**
 * Utility facilitating creating new requests out of the socket.
 *
 * @author Piotr Polak piotr [at] polak [dot] ro
 * @since 201611
 */
public class HttpRequestWrapperFactory {

    private static final String BOUNDARY_START = "boundary=";
    private static final int URI_MAX_LENGTH = 2048;
    private static final int STATUS_MAX_LENGTH = 8 + URI_MAX_LENGTH + 9; // CONNECT + space + URI + space + HTTP/1.0
    private static final String[] RECOGNIZED_METHODS = {"OPTIONS", "GET", "HEAD", "POST", "PUT", "DELETE", "TRACE", "CONNECT"};
    private static final int METHOD_MAX_LENGTH;
    private static final List<String> RECOGNIZED_METHODS_LIST = Arrays.asList(RECOGNIZED_METHODS);
    private static final String HEADERS_END_DELIMINATOR = "\n\r\n";

    static {
        int maxMethodLength = 0;
        for (String method : RECOGNIZED_METHODS) {
            if (method.length() > maxMethodLength) {
                maxMethodLength = method.length();
            }
        }
        METHOD_MAX_LENGTH = maxMethodLength;
    }

    private static final Parser<Headers> headersParser = new HeadersParser();
    private static final Parser<Map<String, String>> queryStringParser = new QueryStringParser();
    private static final Parser<RequestStatus> statusParser = new RequestStatusParser();
    private static final Parser<Map<String, Cookie>> cookieParser = new CookieParser();

    private final String tempPath;

    /**
     * Default constructor.
     *
     * @param tempPath
     */
    public HttpRequestWrapperFactory(String tempPath) {
        this.tempPath = tempPath;
    }

    /**
     * Creates and returns a request out of the socket.
     *
     * @param socket
     * @return
     */
    public HttpRequestWrapper createFromSocket(Socket socket)
            throws IOException, StatusLineTooLongProtocolException,
            MalformedOrUnsupporedMethodProtocolException, UriTooLongProtocolException {

        HttpRequestWrapper request = new HttpRequestWrapper();

        InputStream in = socket.getInputStream();
        // The order matters
        RequestStatus status = statusParser.parse(getStatusLine(in));

        int uriLengthExceededWith = status.getUri().length() - URI_MAX_LENGTH;
        if (uriLengthExceededWith > 0) {
            throw new UriTooLongProtocolException("Uri length exceeded max length with" + uriLengthExceededWith + " characters");
        }

        request.setInputStream(in);
        assignSocketMetadata(socket, request);
        request.setStatus(status);
        request.setGetParameters(queryStringParser.parse(status.getQueryString()));

        String headersString = getHeaders(in);
        if (headersString.length() > 3) {
            request.setHeaders(headersParser.parse(headersString));
            request.setCookies(getCookies(request.getHeaders()));
        } else {
            request.setHeaders(new Headers()); // Setting implicit empty headers
        }

        if (request.getMethod().toUpperCase().equals(HttpRequestWrapper.METHOD_POST)) {
            handlePostRequest(request, in);
        }

        return request;
    }

    private void assignSocketMetadata(Socket socket, HttpRequestWrapper request) {
        request.setSecure(false);
        request.setScheme("http");
        request.setRemoteAddr(socket.getInetAddress().getHostAddress());
        request.setRemotePort(((InetSocketAddress) socket.getRemoteSocketAddress()).getPort());
        request.setRemoteHost(((InetSocketAddress) socket.getRemoteSocketAddress()).getHostName());
        request.setLocalAddr(socket.getLocalAddress().getHostAddress());
        request.setLocalPort(socket.getLocalPort());
        request.setServerPort(socket.getLocalPort());
        request.setLocalName(socket.getLocalAddress().getHostName());
        request.setServerName(socket.getInetAddress().getHostName());
    }

    private Map<String, Cookie> getCookies(Headers headers) {
        if (headers.containsHeader(Headers.HEADER_COOKIE)) {
            return cookieParser.parse(headers.getHeader(Headers.HEADER_COOKIE));
        }
        return new HashMap<>();
    }

    private String getStatusLine(InputStream in)
            throws IOException, StatusLineTooLongProtocolException, MalformedOrUnsupporedMethodProtocolException {
        StringBuilder statusLine = new StringBuilder();
        byte[] buffer = new byte[1];
        int length = 0;
        boolean wasMethodRead = false;
        while (in.read(buffer, 0, buffer.length) != -1) {

            ++length;

            if (buffer[0] == '\n') {
                break;
            }
            statusLine.append((char) buffer[0]);

            if (!wasMethodRead) {
                if (buffer[0] == ' ') {
                    wasMethodRead = true;
                    String method = statusLine.substring(0, statusLine.length() - 1).toUpperCase();
                    if (!RECOGNIZED_METHODS_LIST.contains(method)) {
                        throw new MalformedOrUnsupporedMethodProtocolException("Method " + method + " is not supported");
                    }
                } else {
                    if (length > METHOD_MAX_LENGTH) {
                        throw new MalformedOrUnsupporedMethodProtocolException("Method name is longer than expected");
                    }
                }
            }

            if (length > STATUS_MAX_LENGTH) {
                throw new StatusLineTooLongProtocolException("Exceeded max size of " + STATUS_MAX_LENGTH);
            }
        }
        Statistics.addBytesReceived(statusLine.length() + 1);

        return statusLine.toString();
    }

    private String getHeaders(InputStream in) throws IOException {
        StringBuilder headersString = new StringBuilder();
        byte[] buffer;
        buffer = new byte[1];
        int headersEndSymbolLength = HEADERS_END_DELIMINATOR.length();

        while (in.read(buffer, 0, buffer.length) != -1) {
            headersString.append((char) buffer[0]);
            if (headersString.length() > headersEndSymbolLength) {
                String endChars = headersString.substring(headersString.length() - headersEndSymbolLength, headersString.length());
                if (endChars.equals(HEADERS_END_DELIMINATOR)) {
                    headersString.setLength(headersString.length() - headersEndSymbolLength);
                    break;
                }
            }
        }

        Statistics.addBytesReceived(headersString.length() + headersEndSymbolLength);
        return headersString.toString();
    }

    private void handlePostRequest(HttpRequestWrapper request, InputStream in) throws IOException {
        int postLength = 0;
        if (request.getHeaders().containsHeader(request.getHeaders().HEADER_CONTENT_LENGTH)) {
            try {
                postLength = Integer.parseInt(request.getHeaders().getHeader(request.getHeaders().HEADER_CONTENT_LENGTH));
            } catch (NumberFormatException e) {
            }
        }

        // Only if post length is greater than 0
        // Keep 0 value - makes no sense to parse the data
        if (postLength < 1) {
            return;
        }

        if (isMultipartRequest(request)) {
            handlePostMultipartRequest(request, in, postLength);
        } else {
            handlePostPlainRequest(request, in, postLength);
        }
    }

    private boolean isMultipartRequest(HttpRequestWrapper request) {
        return request.getHeaders().containsHeader(Headers.HEADER_CONTENT_TYPE)
                && request.getHeaders().getHeader(Headers.HEADER_CONTENT_TYPE).toLowerCase().startsWith("multipart/form-data");
    }

    private void handlePostPlainRequest(HttpRequestWrapper request, InputStream in, int postLength) throws IOException {
        byte[] buffer;
        buffer = new byte[1];
        StringBuilder postLine = new StringBuilder();
        while (in.read(buffer, 0, buffer.length) != -1) {
            postLine.append((char) buffer[0]);
            if (postLine.length() >= postLength) {
                // Forced "the end"
                break;
            }
        }
        request.setPostParameters(queryStringParser.parse(postLine.toString()));
        Statistics.addBytesReceived(postLine.length());
    }

    private void handlePostMultipartRequest(HttpRequestWrapper request, InputStream in, int postLength) throws IOException {
        String boundary = request.getHeaders().getHeader(Headers.HEADER_CONTENT_TYPE);
        int boundaryPosition = boundary.toLowerCase().indexOf(BOUNDARY_START);
        if (boundaryPosition > -1) {
            int boundaryStartPos = boundaryPosition + BOUNDARY_START.length();
            if (boundaryStartPos < boundary.length()) {
                boundary = boundary.substring(boundaryStartPos, boundary.length());
                MultipartRequestHandler mrh = new MultipartRequestHandler(in, postLength, boundary, tempPath);
                mrh.handle();

                request.setPostParameters(mrh.getPost());
                request.setFileUpload(new FileUpload(mrh.getUploadedFiles()));
            }
        }
    }
}
