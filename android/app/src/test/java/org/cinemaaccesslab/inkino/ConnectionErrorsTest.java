// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import static org.junit.Assert.assertEquals;

import org.cinemaaccesslab.inkino.ConnectionErrors.Kind;
import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;

import javax.net.ssl.SSLHandshakeException;

public class ConnectionErrorsTest {
    @Test
    public void closedConnectionMeansNoServer() {
        IOException eof =
                new IOException("unexpected end of stream on com.android.okhttp.Address@191d5601");
        assertEquals(Kind.NO_SERVER, ConnectionErrors.classify(eof));
        assertEquals(Kind.NO_SERVER, ConnectionErrors.classify(new ConnectException("refused")));
    }

    @Test
    public void causesAreFollowed() {
        IOException wrapped = new IOException("fetch failed", new SocketTimeoutException());
        assertEquals(Kind.TIMEOUT, ConnectionErrors.classify(wrapped));
    }

    @Test
    public void httpStatusesAreSeparated() {
        assertEquals(
                Kind.NOT_FOUND,
                ConnectionErrors.classify(new ConnectionErrors.HttpStatus("Catalogue", 404)));
        IOException serverError = new ConnectionErrors.HttpStatus("Asset", 500);
        assertEquals(Kind.HTTP_ERROR, ConnectionErrors.classify(serverError));
        assertEquals(500, ConnectionErrors.httpCode(serverError));
    }

    @Test
    public void otherKinds() {
        assertEquals(
                Kind.UNKNOWN_HOST,
                ConnectionErrors.classify(new UnknownHostException("example.invalid")));
        assertEquals(
                Kind.HTTPS_REQUIRED,
                ConnectionErrors.classify(
                        new UnknownServiceException(
                                "CLEARTEXT communication to 10.0.0.2 not permitted")));
        assertEquals(
                Kind.SECURE_CONNECTION,
                ConnectionErrors.classify(new SSLHandshakeException("bad certificate")));
        assertEquals(Kind.OTHER, ConnectionErrors.classify(new IOException("disk full")));
    }
}
