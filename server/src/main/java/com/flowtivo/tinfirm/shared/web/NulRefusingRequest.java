package com.flowtivo.tinfirm.shared.web;

import java.io.BufferedReader;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;

import org.apache.tomcat.util.http.fileupload.InvalidFileNameException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * The request as every filter and handler reads it, refusing U+0000 (NUL) in what may reach a SQL parameter as text
 * (PostgreSQL refuses it in {@code text} and {@code jsonb}): a query or form parameter, its name, a part's name and
 * file name, and a string of a JSON body. Each is checked when it is read, so that nothing is parsed or read earlier
 * than without the check: a multipart body stays unread until its handler, after authentication, asks for a part.
 * <p>
 * A parameter or a part is refused with {@code 400 VALIDATION_FAILED} ({@link ApiException}), naming the parameter
 * when its name is a plain one. A JSON body's {@code read} throws {@link NulCharacterException}, so that its reader
 * fails as for a body it cannot read. Only the escape {@code \u0000} gives a JSON string a NUL; a raw NUL byte is no
 * valid UTF-8 JSON (RFC 8259 asks for UTF-8 between systems; UTF-16 and UTF-32 bodies hold NUL bytes and are refused
 * with it). The body is scanned as its parser decodes it: as UTF-8 bytes, the overlong forms of U+0000 included
 * ({@code C0 80}, {@code E0 80 80}, {@code F0 80 80 80}, which the parser decodes to it), or, when its Content-Type
 * names another charset than UTF-8, UTF-16 or UTF-32, as the text of that charset, which Spring MVC's converter reads
 * it as. A path cannot hold NUL: Tomcat refuses {@code %00} in a path with 400 before any filter.
 */
final class NulRefusingRequest extends HttpServletRequestWrapper {

    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_.\\-\\[\\]]{1,64}");

    private final boolean scanBody;

    private boolean parametersChecked;

    private boolean partsChecked;

    private @Nullable ServletInputStream body;

    private @Nullable BufferedReader reader;

    /** @param scanBody whether a JSON body is scanned: false for the paths that take arbitrary bodies */
    NulRefusingRequest(HttpServletRequest request, boolean scanBody) {
        super(request);
        this.scanBody = scanBody;
    }

    @Override
    public @Nullable String getParameter(String name) {
        return checked(name, super.getParameter(name));
    }

    @Override
    public String @Nullable [] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values != null) {
            for (String value : values) {
                checked(name, value);
            }
        }
        return values;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> parameters = super.getParameterMap();
        if (!parametersChecked) {
            parameters.forEach((name, values) -> {
                checked(name, name);
                for (String value : values) {
                    checked(name, value);
                }
            });
            parametersChecked = true;
        }
        return parameters;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        getParameterMap();
        return super.getParameterNames();
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        Collection<Part> parts;
        try {
            parts = super.getParts();
        }
        catch (ServletException ex) {
            // Tomcat refuses a file name with a raw NUL itself; its message quotes the name.
            if (ex.getCause() instanceof InvalidFileNameException) {
                throw ApiException.validation(NulCharacterException.DETAIL);
            }
            throw ex;
        }
        if (!partsChecked) {
            parts.forEach(NulRefusingRequest::check);
            partsChecked = true;
        }
        return parts;
    }

    @Override
    public @Nullable Part getPart(String name) throws IOException, ServletException {
        Part part = super.getPart(name);
        if (part != null) {
            check(part);
        }
        return part;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (!scanBody || !json()) {
            return super.getInputStream();
        }
        if (body == null) {
            body = new ScannedInputStream(super.getInputStream(), textCharset());
        }
        return body;
    }

    /**
     * The charset that Spring MVC's JSON converter decodes the body with as text: the one the Content-Type names
     * unless it is UTF-8, UTF-16 or UTF-32, which the parser reads as bytes itself. Null for those, for none, and for
     * a Content-Type or charset that cannot be read, which the converter refuses.
     */
    private @Nullable Charset textCharset() {
        Charset charset;
        try {
            charset = MediaType.parseMediaType(getContentType()).getCharset();
        }
        catch (IllegalArgumentException ex) {
            // InvalidMediaTypeException among them, an unsupported charset included.
            return null;
        }
        if (charset == null) {
            return null;
        }
        String name = charset.name();
        return name.startsWith("UTF-8") || name.startsWith("UTF-16") || name.startsWith("UTF-32") ? null : charset;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (!scanBody || !json()) {
            return super.getReader();
        }
        if (reader == null) {
            reader = new BufferedReader(new ScannedReader(super.getReader()));
        }
        return reader;
    }

    private boolean json() {
        String type = getContentType();
        if (type == null) {
            return false;
        }
        int end = type.indexOf(';');
        String mediaType = (end < 0 ? type : type.substring(0, end)).trim().toLowerCase(java.util.Locale.ROOT);
        return mediaType.equals("application/json") || mediaType.startsWith("application/") && mediaType.endsWith("+json");
    }

    private static @Nullable String checked(String name, @Nullable String value) {
        if (value != null && value.indexOf('\0') >= 0) {
            throw refusal(name);
        }
        return value;
    }

    /** A part's name and its file's name: Tomcat's, and the one Spring MVC reads from the header ({@code filename*}). */
    private static void check(Part part) {
        checked(part.getName(), part.getName());
        checked(part.getName(), part.getSubmittedFileName());
        String disposition = part.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        if (disposition != null) {
            ContentDisposition parsed;
            try {
                parsed = ContentDisposition.parse(disposition);
            }
            catch (IllegalArgumentException ex) {
                // Spring MVC refuses the part itself when it reads it.
                return;
            }
            checked(part.getName(), parsed.getName());
            checked(part.getName(), parsed.getFilename());
        }
    }

    private static ApiException refusal(String name) {
        return name.indexOf('\0') < 0 && PLAIN_NAME.matcher(name).matches()
                ? ApiException.invalid(NulCharacterException.DETAIL, name)
                : ApiException.validation(NulCharacterException.DETAIL);
    }

    /**
     * Finds {@code \u0000} in JSON text, one character at a time: a backslash starts an escape, whose next character
     * ends it unless it is {@code u}, which four hexadecimal digits follow. An escaped backslash ({@code \\u0000}) is
     * no escape of U+0000. Outside a string a backslash is no valid JSON, which the parser refuses itself.
     */
    private static final class Scanner {

        private int state;

        private int zeros;

        void accept(int c) throws NulCharacterException {
            if (c == 0) {
                throw new NulCharacterException();
            }
            switch (state) {
                case 0 -> state = c == '\\' ? 1 : 0;
                case 1 -> {
                    state = c == 'u' ? 2 : 0;
                    zeros = 0;
                }
                default -> {
                    if (c != '0') {
                        state = c == '\\' ? 1 : 0;
                    }
                    else if (++zeros == 4) {
                        throw new NulCharacterException();
                    }
                }
            }
        }
    }

    /**
     * The body's bytes, scanned as they pass: as UTF-8 when {@code charset} is null, else as the text of that charset,
     * decoded as Spring MVC's converter decodes it (a malformed or unmappable byte as U+FFFD).
     */
    private static final class ScannedInputStream extends ServletInputStream {

        private final ServletInputStream in;

        private final Scanner scanner = new Scanner();

        private final @Nullable CharsetDecoder decoder;

        private final CharBuffer chars = CharBuffer.allocate(1024);

        /** Bytes of a character that the next read completes, for {@link #decoder}. */
        private ByteBuffer pending = ByteBuffer.allocate(0);

        /** In UTF-8: how many bytes {@code 80} still end an overlong U+0000 ({@code C0}, {@code E0}, {@code F0}). */
        private int overlong;

        ScannedInputStream(ServletInputStream in, @Nullable Charset charset) {
            this.in = in;
            this.decoder = charset == null ? null : charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                scan(new byte[] {(byte) b}, 0, 1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                scan(buffer, offset, n);
            }
            return n;
        }

        private void scan(byte[] bytes, int offset, int length) throws NulCharacterException {
            if (decoder == null) {
                for (int i = offset; i < offset + length; i++) {
                    int b = bytes[i] & 0xff;
                    utf8(b);
                    scanner.accept(b);
                }
                return;
            }
            ByteBuffer input = ByteBuffer.allocate(pending.remaining() + length);
            input.put(pending).put(bytes, offset, length).flip();
            CoderResult result;
            do {
                result = decoder.decode(input, chars, false);
                chars.flip();
                while (chars.hasRemaining()) {
                    scanner.accept(chars.get());
                }
                chars.clear();
            }
            while (result.isOverflow());
            pending = input.slice();
        }

        /** The overlong forms of U+0000, which UTF-8 does not allow and the JSON parser decodes all the same. */
        private void utf8(int b) throws NulCharacterException {
            if (overlong > 0) {
                if (b == 0x80) {
                    if (--overlong == 0) {
                        throw new NulCharacterException();
                    }
                    return;
                }
                overlong = 0;
            }
            overlong = b == 0xC0 ? 1 : b == 0xE0 ? 2 : b == 0xF0 ? 3 : 0;
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class ScannedReader extends FilterReader {

        private final Scanner scanner = new Scanner();

        ScannedReader(Reader in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (c >= 0) {
                scanner.accept(c);
            }
            return c;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            for (int i = 0; i < n; i++) {
                scanner.accept(buffer[offset + i]);
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            // Skipped characters are read too, so that none passes unscanned.
            char[] skipped = new char[(int) Math.min(n, 8192)];
            int read = read(skipped, 0, skipped.length);
            return Math.max(read, 0);
        }
    }
}
