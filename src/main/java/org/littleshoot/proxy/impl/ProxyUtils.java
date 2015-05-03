package org.littleshoot.proxy.impl;

import com.google.common.collect.ImmutableSet;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Utilities for the proxy.
 */
public class ProxyUtils {
    /**
     * Hop-by-hop headers that should be removed when proxying, as defined by the HTTP 1.1 spec, section 13.5.1
     * (http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.5.1). Transfer-Encoding is NOT included in this list, since LittleProxy
     * does not typically modify the transfer encoding. See also {@link #shouldRemoveHopByHopHeader(String)}.
     *
     * Header names are stored as lowercase to make case-insensitive comparisons easier.
     */
    private static final Set<String> SHOULD_NOT_PROXY_HOP_BY_HOP_HEADERS = ImmutableSet.of(
            HttpHeaders.Names.CONNECTION.toLowerCase(Locale.US),
            HttpHeaders.Names.PROXY_AUTHENTICATE.toLowerCase(Locale.US),
            HttpHeaders.Names.PROXY_AUTHORIZATION.toLowerCase(Locale.US),
            HttpHeaders.Names.TE.toLowerCase(Locale.US),
            HttpHeaders.Names.TRAILER.toLowerCase(Locale.US),
            /*  Note: Not removing Transfer-Encoding since LittleProxy does not normally re-chunk content.
                HttpHeaders.Names.TRANSFER_ENCODING.toLowerCase(Locale.US), */
            HttpHeaders.Names.UPGRADE.toLowerCase(Locale.US),
            "Keep-Alive".toLowerCase(Locale.US)
    );

    private static final Logger LOG = LoggerFactory.getLogger(ProxyUtils.class);

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    /**
     * Date format pattern used to parse HTTP date headers in RFC 1123 format.
     */
    private static final String PATTERN_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

    private static final String hostName = getHostName();

    // Should never be constructed.
    private ProxyUtils() {
    }

    // Schemes are case-insensitive:
    // http://tools.ietf.org/html/rfc3986#section-3.1
    private static Pattern HTTP_PREFIX = Pattern.compile("^https?://.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Strips the host from a URI string. This will turn "http://host.com/path"
     * into "/path".
     * 
     * @param uri
     *            The URI to transform.
     * @return A string with the URI stripped.
     */
    public static String stripHost(final String uri) {
        if (!HTTP_PREFIX.matcher(uri).matches()) {
            // It's likely a URI path, not the full URI (i.e. the host is
            // already stripped).
            return uri;
        }
        final String noHttpUri = StringUtils.substringAfter(uri, "://");
        final int slashIndex = noHttpUri.indexOf("/");
        if (slashIndex == -1) {
            return "/";
        }
        final String noHostUri = noHttpUri.substring(slashIndex);
        return noHostUri;
    }

    /**
     * Formats the given date according to the RFC 1123 pattern.
     * 
     * @param date
     *            The date to format.
     * @return An RFC 1123 formatted date string.
     * 
     * @see #PATTERN_RFC1123
     */
    public static String formatDate(final Date date) {
        return formatDate(date, PATTERN_RFC1123);
    }

    /**
     * Formats the given date according to the specified pattern. The pattern
     * must conform to that used by the {@link SimpleDateFormat simple date
     * format} class.
     * 
     * @param date
     *            The date to format.
     * @param pattern
     *            The pattern to use for formatting the date.
     * @return A formatted date string.
     * 
     * @throws IllegalArgumentException
     *             If the given date pattern is invalid.
     * 
     * @see SimpleDateFormat
     */
    public static String formatDate(final Date date, final String pattern) {
        if (date == null)
            throw new IllegalArgumentException("date is null");
        if (pattern == null)
            throw new IllegalArgumentException("pattern is null");

        final SimpleDateFormat formatter = new SimpleDateFormat(pattern,
                Locale.US);
        formatter.setTimeZone(GMT);
        return formatter.format(date);
    }

    /**
     * If an HttpObject implements the market interface LastHttpContent, it
     * represents the last chunk of a transfer.
     * 
     * @see io.netty.handler.codec.http.LastHttpContent
     * 
     * @param httpObject
     * @return
     * 
     */
    public static boolean isLastChunk(final HttpObject httpObject) {
        return httpObject instanceof LastHttpContent;
    }

    /**
     * If an HttpObject is not the last chunk, then that means there are other
     * chunks that will follow.
     * 
     * @see io.netty.handler.codec.http.FullHttpMessage
     * 
     * @param httpObject
     * @return
     */
    public static boolean isChunked(final HttpObject httpObject) {
        return !isLastChunk(httpObject);
    }

    /**
     * Parses the host and port an HTTP request is being sent to.
     * 
     * @param httpRequest
     *            The request.
     * @return The host and port string.
     */
    public static String parseHostAndPort(final HttpRequest httpRequest) {
        final String uriHostAndPort = parseHostAndPort(httpRequest.getUri());
        return uriHostAndPort;
    }

    /**
     * Parses the host and port an HTTP request is being sent to.
     * 
     * @param uri
     *            The URI.
     * @return The host and port string.
     */
    public static String parseHostAndPort(final String uri) {
        final String tempUri;
        if (!HTTP_PREFIX.matcher(uri).matches()) {
            // Browsers particularly seem to send requests in this form when
            // they use CONNECT.
            tempUri = uri;
        } else {
            // We can't just take a substring from a hard-coded index because it
            // could be either http or https.
            tempUri = StringUtils.substringAfter(uri, "://");
        }
        final String hostAndPort;
        if (tempUri.contains("/")) {
            hostAndPort = tempUri.substring(0, tempUri.indexOf("/"));
        } else {
            hostAndPort = tempUri;
        }
        return hostAndPort;
    }

    /**
     * Make a copy of the response including all mutable fields.
     * 
     * @param original
     *            The original response to copy from.
     * @return The copy with all mutable fields from the original.
     */
    public static HttpResponse copyMutableResponseFields(
            final HttpResponse original) {

        HttpResponse copy = null;
        if (original instanceof DefaultFullHttpResponse) {
            ByteBuf content = ((DefaultFullHttpResponse) original).content();
            copy = new DefaultFullHttpResponse(original.getProtocolVersion(),
                    original.getStatus(), content);
        } else {
            copy = new DefaultHttpResponse(original.getProtocolVersion(),
                    original.getStatus());
        }
        final Collection<String> headerNames = original.headers().names();
        for (final String name : headerNames) {
            final List<String> values = original.headers().getAll(name);
            copy.headers().set(name, values);
        }
        return copy;
    }

    /**
     * Adds the Via header to specify that the message has passed through the
     * proxy.
     * 
     * @param msg
     *            The HTTP message.
     */
    public static void addVia(final HttpMessage msg) {
        final StringBuilder sb = new StringBuilder();
        sb.append(msg.getProtocolVersion().majorVersion());
        sb.append('.');
        sb.append(msg.getProtocolVersion().minorVersion());
        sb.append(' ');
        sb.append(hostName);
        final List<String> vias;
        if (msg.headers().contains(HttpHeaders.Names.VIA)) {
            vias = msg.headers().getAll(HttpHeaders.Names.VIA);
            vias.add(sb.toString());
        } else {
            vias = Collections.singletonList(sb.toString());
        }
        msg.headers().set(HttpHeaders.Names.VIA, vias);
    }

    /**
     * Returns <code>true</code> if the specified string is either "true" or
     * "on" ignoring case.
     * 
     * @param val
     *            The string in question.
     * @return <code>true</code> if the specified string is either "true" or
     *         "on" ignoring case, otherwise <code>false</code>.
     */
    public static boolean isTrue(final String val) {
        return checkTrueOrFalse(val, "true", "on");
    }

    /**
     * Returns <code>true</code> if the specified string is either "false" or
     * "off" ignoring case.
     * 
     * @param val
     *            The string in question.
     * @return <code>true</code> if the specified string is either "false" or
     *         "off" ignoring case, otherwise <code>false</code>.
     */
    public static boolean isFalse(final String val) {
        return checkTrueOrFalse(val, "false", "off");
    }

    public static boolean extractBooleanDefaultFalse(final Properties props,
            final String key) {
        final String throttle = props.getProperty(key);
        if (StringUtils.isNotBlank(throttle)) {
            return throttle.trim().equalsIgnoreCase("true");
        }
        return false;
    }

    public static boolean extractBooleanDefaultTrue(final Properties props,
            final String key) {
        final String throttle = props.getProperty(key);
        if (StringUtils.isNotBlank(throttle)) {
            return throttle.trim().equalsIgnoreCase("true");
        }
        return true;
    }
    
    public static int extractInt(final Properties props, final String key) {
        return extractInt(props, key, -1);
    }
    
    public static int extractInt(final Properties props, final String key, int defaultValue) {
        final String readThrottleString = props.getProperty(key);
        if (StringUtils.isNotBlank(readThrottleString) &&
            NumberUtils.isNumber(readThrottleString)) {
            return Integer.parseInt(readThrottleString);
        }
        return defaultValue;
    }

    public static boolean isCONNECT(HttpObject httpObject) {
        return httpObject instanceof HttpRequest
                && HttpMethod.CONNECT.equals(((HttpRequest) httpObject)
                        .getMethod());
    }

    private static boolean checkTrueOrFalse(final String val,
            final String str1, final String str2) {
        final String str = val.trim();
        return StringUtils.isNotBlank(str)
                && (str.equalsIgnoreCase(str1) || str.equalsIgnoreCase(str2));
    }

    /**
     * Attempts to resolve the local machine's hostname.
     *
     * @return the local machine's hostname
     * @throws IllegalStateException if the hostname cannot be resolved
     */
    public static String getHostName() throws IllegalStateException {
        try {
            final InetAddress localAddress = NetworkUtils.getLocalHost();
            return localAddress.getHostName();
        } catch (final UnknownHostException e) {
            LOG.error("Could not lookup host", e);
            throw new IllegalStateException("Could not determine host!", e);
        }
    }

    /**
     * Determines if the specified header should be removed from the proxied response because it is a hop-by-hop header, as defined by the
     * HTTP 1.1 spec in section 13.5.1. The comparison is case-insensitive, so "Connection" will be treated the same as "connection" or "CONNECTION".
     * From http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.5.1 :
     * <pre>
       The following HTTP/1.1 headers are hop-by-hop headers:
        - Connection
        - Keep-Alive
        - Proxy-Authenticate
        - Proxy-Authorization
        - TE
        - Trailers [LittleProxy note: actual header name is Trailer]
        - Transfer-Encoding [LittleProxy note: this header is not normally removed when proxying, since the proxy does not re-chunk
                            responses. The exception is when an HttpObjectAggregator is enabled, which aggregates chunked content and removes
                            the 'Transfer-Encoding: chunked' header itself.]
        - Upgrade

       All other headers defined by HTTP/1.1 are end-to-end headers.
     * </pre>
     *
     * @param headerName the header name
     * @return true if this header is a hop-by-hop header and should be removed when proxying, otherwise false
     */
    public static boolean shouldRemoveHopByHopHeader(String headerName) {
        return SHOULD_NOT_PROXY_HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.US));
    }
}
