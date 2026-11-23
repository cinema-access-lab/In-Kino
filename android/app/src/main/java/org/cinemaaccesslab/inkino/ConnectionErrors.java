// SPDX-License-Identifier: Apache-2.0
package org.cinemaaccesslab.inkino;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;

import javax.net.ssl.SSLException;

/** Sorts network failures into the few cases a user can do something about. */
final class ConnectionErrors {
    enum Kind {
        NO_SERVER,
        TIMEOUT,
        UNKNOWN_HOST,
        NOT_FOUND,
        HTTP_ERROR,
        HTTPS_REQUIRED,
        SECURE_CONNECTION,
        OTHER
    }

    /** A response with a status other than 200. */
    static final class HttpStatus extends IOException {
        final int code;

        HttpStatus(String what, int code) {
            super(what + " HTTP " + code);
            this.code = code;
        }
    }

    private ConnectionErrors() {}

    static Kind classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpStatus) {
                return ((HttpStatus) t).code == 404 ? Kind.NOT_FOUND : Kind.HTTP_ERROR;
            }
            if (t instanceof SocketTimeoutException) return Kind.TIMEOUT;
            if (t instanceof UnknownHostException) return Kind.UNKNOWN_HOST;
            if (t instanceof SSLException) return Kind.SECURE_CONNECTION;
            String message = String.valueOf(t.getMessage());
            if (t instanceof UnknownServiceException && message.contains("CLEARTEXT")) {
                return Kind.HTTPS_REQUIRED;
            }
            if (t instanceof ConnectException
                    || t instanceof NoRouteToHostException
                    || t instanceof SocketException
                    || t instanceof EOFException
                    || message.contains("unexpected end of stream")) {
                // A closed connection without any response usually means nothing is listening,
                // for example behind "adb reverse" when the computer's server isn't running.
                return Kind.NO_SERVER;
            }
        }
        return Kind.OTHER;
    }

    static int httpCode(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpStatus) return ((HttpStatus) t).code;
        }
        return 0;
    }
}
