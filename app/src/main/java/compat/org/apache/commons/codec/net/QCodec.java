/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package compat.org.apache.commons.codec.net;

import java.io.UnsupportedEncodingException;
import java.util.BitSet;

import compat.org.apache.commons.codec.CharEncoding;
import compat.org.apache.commons.codec.DecoderException;
import compat.org.apache.commons.codec.EncoderException;
import compat.org.apache.commons.codec.StringDecoder;
import compat.org.apache.commons.codec.StringEncoder;

/**
 * <p>
 * Similar to the Quoted-Printable content-transfer-encoding defined in <a
 * href="http://www.ietf.org/rfc/rfc1521.txt">RFC 1521</a> and designed to allow text containing mostly ASCII
 * characters to be decipherable on an ASCII terminal without decoding.
 * </p>
 * 
 * <p>
 * <a href="http://www.ietf.org/rfc/rfc1522.txt">RFC 1522</a> describes techniques to allow the encoding of non-ASCII
 * text in various portions of a RFC 822 [2] message header, in a manner which is unlikely to confuse existing message
 * handling software.
 * </p>
 * 
 * @see <a href="http://www.ietf.org/rfc/rfc1522.txt">MIME (Multipurpose Internet Mail Extensions) Part Two: Message
 *          Header Extensions for Non-ASCII Text</a>
 * 
 * @author Apache Software Foundation
 * @since 1.3
 * @version $Id: QCodec.java 1170351 2011-09-13 21:09:09Z ggregory $
 */
public class QCodec extends RFC1522Codec implements StringEncoder, StringDecoder {
    /**
     * The default charset used for string decoding and encoding.
     */
    private final String charset;

    /**
     * BitSet of printable characters as defined in RFC 1522.
     */
    private static final BitSet PRINTABLE_CHARS = new BitSet(256);
    // Static initializer for printable chars collection
    static {
        // alpha characters
        PRINTABLE_CHARS.set(' ');
        PRINTABLE_CHARS.set('!');
        PRINTABLE_CHARS.set('"');
        PRINTABLE_CHARS.set('#');
        PRINTABLE_CHARS.set('$');
        PRINTABLE_CHARS.set('%');
        PRINTABLE_CHARS.set('&');
        PRINTABLE_CHARS.set('\'');
        PRINTABLE_CHARS.set('(');
        PRINTABLE_CHARS.set(')');
        PRINTABLE_CHARS.set('*');
        PRINTABLE_CHARS.set('+');
        PRINTABLE_CHARS.set(',');
        PRINTABLE_CHARS.set('-');
        PRINTABLE_CHARS.set('.');
        PRINTABLE_CHARS.set('/');
        for (int i = '0'; i <= '9'; i++) {
            PRINTABLE_CHARS.set(i);
        }
        PRINTABLE_CHARS.set(':');
        PRINTABLE_CHARS.set(';');
        PRINTABLE_CHARS.set('<');
        PRINTABLE_CHARS.set('>');
        PRINTABLE_CHARS.set('@');
        for (int i = 'A'; i <= 'Z'; i++) {
            PRINTABLE_CHARS.set(i);
        }
        PRINTABLE_CHARS.set('[');
        PRINTABLE_CHARS.set('\\');
        PRINTABLE_CHARS.set(']');
        PRINTABLE_CHARS.set('^');
        PRINTABLE_CHARS.set('`');
        for (int i = 'a'; i <= 'z'; i++) {
            PRINTABLE_CHARS.set(i);
        }
        PRINTABLE_CHARS.set('{');
        PRINTABLE_CHARS.set('|');
        PRINTABLE_CHARS.set('}');
        PRINTABLE_CHARS.set('~');
    }

    private static final byte BLANK = 32;

    private static final byte UNDERSCORE = 95;

    private boolean encodeBlanks = false;

    /**
     * Default constructor.
     */
    public QCodec() {
        this(CharEncoding.UTF_8);
    }

    /**
     * Constructor which allows for the selection of a default charset
     * 
     * @param charset
     *                  the default string charset to use.
     * 
     * @see <a href="http://download.oracle.com/javase/1.5.0/docs/api/java/nio/charset/Charset.html">Standard charsets</a>
     */
    public QCodec(final String charset) {
        super();
        this.charset = charset;
    }

    @Override
    protected String getEncoding() {
        return "Q";
    }

    @Override
    protected byte[] doEncoding(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        byte[] data = QuotedPrintableCodec.encodeQuotedPrintable(PRINTABLE_CHARS, bytes);
        if (this.encodeBlanks) {
            for (int i = 0; i < data.length; i++) {
                if (data[i] == BLANK) {
                    data[i] = UNDERSCORE;
                }
            }
        }
        return data;
    }

    @Override
    protected byte[] doDecoding(byte[] bytes) throws DecoderException {
        if (bytes == null) {
            return null;
        }
        boolean hasUnderscores = false;
        for (byte b : bytes) {
            if (b == UNDERSCORE) {
                hasUnderscores = true;
                break;
            }
        }
        if (hasUnderscores) {
            byte[] tmp = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                byte b = bytes[i];
                if (b != UNDERSCORE) {
                    tmp[i] = b;
                } else {
                    tmp[i] = BLANK;
                }
            }
            return QuotedPrintableCodec.decodeQuotedPrintable(tmp);
        } 
        return QuotedPrintableCodec.decodeQuotedPrintable(bytes);       
    }

    /**
     * Encodes a string into its quoted-printable form using the specified charset. Unsafe characters are escaped.
     * 
     * @param pString
     *                  string to convert to quoted-printable form
     * @param charset
     *                  the charset for pString
     * @return quoted-printable string
     * 
     * @throws EncoderException
     *                  thrown if a failure condition is encountered during the encoding process.
     */
    public String encode(final String pString, final String charset) throws EncoderException {
        if (pString == null) {
            return null;
        }
        try {
            return encodeText(pString, charset);
        } catch (UnsupportedEncodingException e) {
            throw new EncoderException(e.getMessage(), e);
        }
    }

    /**
     * Encodes a string into its quoted-printable form using the default charset. Unsafe characters are escaped.
     * 
     * @param pString
     *                  string to convert to quoted-printable form
     * @return quoted-printable string
     * 
     * @throws EncoderException
     *                  thrown if a failure condition is encountered during the encoding process.
     */
    public String encode(String pString) throws EncoderException {
        if (pString == null) {
            return null;
        }
        return encode(pString, getDefaultCharset());
    }

    /**
     * Decodes a quoted-printable string into its original form. Escaped characters are converted back to their original
     * representation.
     * 
     * @param pString
     *                  quoted-printable string to convert into its original form
     * 
     * @return original string
     * 
     * @throws DecoderException
     *                  A decoder exception is thrown if a failure condition is encountered during the decode process.
     */
    public String decode(String pString) throws DecoderException {
        if (pString == null) {
            return null;
        }
        try {
            return decodeText(pString);
        } catch (UnsupportedEncodingException e) {
            throw new DecoderException(e.getMessage(), e);
        }
    }

    /**
     * Encodes an object into its quoted-printable form using the default charset. Unsafe characters are escaped.
     * 
     * @param pObject
     *                  object to convert to quoted-printable form
     * @return quoted-printable object
     * 
     * @throws EncoderException
     *                  thrown if a failure condition is encountered during the encoding process.
     */
    public Object encode(Object pObject) throws EncoderException {
        if (pObject == null) {
            return null;
        } else if (pObject instanceof String) {
            return encode((String) pObject);
        } else {
            throw new EncoderException("Objects of type " + 
                  pObject.getClass().getName() + 
                  " cannot be encoded using Q codec");
        }
    }

    /**
     * Decodes a quoted-printable object into its original form. Escaped characters are converted back to their original
     * representation.
     * 
     * @param pObject
     *                  quoted-printable object to convert into its original form
     * 
     * @return original object
     * 
     * @throws DecoderException
     *                  Thrown if the argument is not a <code>String</code>. Thrown if a failure condition is
     *                  encountered during the decode process.
     */
    public Object decode(Object pObject) throws DecoderException {
        if (pObject == null) {
            return null;
        } else if (pObject instanceof String) {
            return decode((String) pObject);
        } else {
            throw new DecoderException("Objects of type " + 
                  pObject.getClass().getName() + 
                  " cannot be decoded using Q codec");
        }
    }

    /**
     * The default charset used for string decoding and encoding.
     * 
     * @return the default string charset.
     */
    public String getDefaultCharset() {
        return this.charset;
    }

    /**
     * Tests if optional transformation of SPACE characters is to be used
     * 
     * @return <code>true</code> if SPACE characters are to be transformed, <code>false</code> otherwise
     */
    public boolean isEncodeBlanks() {
        return this.encodeBlanks;
    }

    /**
     * Defines whether optional transformation of SPACE characters is to be used
     * 
     * @param b
     *                  <code>true</code> if SPACE characters are to be transformed, <code>false</code> otherwise
     */
    public void setEncodeBlanks(boolean b) {
        this.encodeBlanks = b;
    }
}
