/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.twitter.hive.util;

import com.facebook.presto.hive.authentication.HiveMetastoreAuthentication;
import com.google.common.net.HostAndPort;
import io.airlift.log.Logger;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

import javax.annotation.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

import static java.util.Objects.requireNonNull;

/**
 * Utility class to handle creating and caching the UserGroupInformation object.
 */
public class PooledTTransportFactory
    extends BasePooledObjectFactory<TTransport>
{
    private static final Logger log = Logger.get(PooledTTransportFactory.class);
    private final TTransportPool pool;
    private final String host;
    private final int port;
    private final HostAndPort socksProxy;
    private final int timeoutMillis;
    private final HiveMetastoreAuthentication metastoreAuthentication;

    public PooledTTransportFactory(TTransportPool pool, String host, int port, @Nullable HostAndPort socksProxy, int timeoutMillis, HiveMetastoreAuthentication metastoreAuthentication)
    {
        this.pool = requireNonNull(pool, "pool is null");
        this.host = requireNonNull(host, "host is null");
        this.port = port;
        this.socksProxy = socksProxy;
        this.timeoutMillis = timeoutMillis;
        this.metastoreAuthentication = requireNonNull(metastoreAuthentication, "metastoreAuthentication is null");
    }

    @Override
    public TTransport create()
        throws Exception
    {
        log.debug("creating a transport to: %s", host);
        TTransport transport;
        if (socksProxy == null) {
            transport = new TSocket(host, port, timeoutMillis);
        }
        else {
            SocketAddress address = InetSocketAddress.createUnresolved(socksProxy.getHostText(), socksProxy.getPort());
            Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS, address));
            try {
                socket.connect(InetSocketAddress.createUnresolved(host, port), timeoutMillis);
                socket.setSoTimeout(timeoutMillis);
                transport = new TSocket(socket);
            }
            catch (SocketException e) {
                if (socket.isConnected()) {
                    try {
                        socket.close();
                    }
                    catch (IOException ioEexception) {
                        // ignored
                    }
                }
                throw e;
            }
        }
        TTransport authenticatedTransport = metastoreAuthentication.authenticate(transport, host);
        if (!authenticatedTransport.isOpen()) {
            authenticatedTransport.open();
        }

        log.debug("created a transport to: %s", host);
        return new PooledTTransport(authenticatedTransport, pool);
    }

    @Override
    public void destroyObject(PooledObject<TTransport> pooledObject)
    {
        log.debug("destroy a transport to: %s", host);
        try {
            ((PooledTTransport) pooledObject.getObject()).getTTransport().close();
        }
        catch (ClassCastException e) {
            log.debug("cannot cast to PooledTTransport");
            // ignore
        }
        pooledObject.invalidate();
    }

    @Override
    public PooledObject<TTransport> wrap(TTransport transport)
    {
        log.debug("wrapping a transport to %s", host);
        return new DefaultPooledObject<TTransport>(transport);
    }

    @Override
    public void passivateObject(PooledObject<TTransport> pooledObject)
    {
        log.debug("passivate a transport to %s", host);
        try {
            pooledObject.getObject().flush();
        }
        catch (TTransportException e) {
            log.debug("Failed to flush transport, destroy it");
            destroyObject(pooledObject);
        }
    }

    private static class PooledTTransport
        extends TTransport
    {
        private final TTransportPool pool;
        private final TTransport transport;

        public PooledTTransport(TTransport transport, TTransportPool pool)
        {
            this.transport = transport;
            this.pool = pool;
        }

        public TTransport getTTransport()
        {
            return transport;
        }

        @Override
        public void close()
        {
            log.debug("attempt to close a PooledTTransport, returning it to pool.");
            try {
                pool.returnObject((TSocket) transport);
            }
            catch (ClassCastException e) {
                pool.returnObject(transport);
            }
        }

        @Override
        public boolean isOpen()
        {
            return transport.isOpen();
        }

        @Override
        public boolean peek()
        {
            return transport.peek();
        }

        @Override
        public byte[] getBuffer()
        {
            return transport.getBuffer();
        }

        @Override
        public int getBufferPosition()
        {
            return transport.getBufferPosition();
        }

        @Override
        public int getBytesRemainingInBuffer()
        {
            return transport.getBytesRemainingInBuffer();
        }

        @Override
        public void consumeBuffer(int len)
        {
            transport.consumeBuffer(len);
        }

        @Override
        public void open()
                throws TTransportException
        {
            transport.open();
        }

        @Override
        public int readAll(byte[] bytes, int off, int len)
                throws TTransportException
        {
            return transport.readAll(bytes, off, len);
        }

        @Override
        public int read(byte[] bytes, int off, int len)
                throws TTransportException
        {
            return transport.read(bytes, off, len);
        }

        @Override
        public void write(byte[] bytes)
                throws TTransportException
        {
            transport.write(bytes);
        }

        @Override
        public void write(byte[] bytes, int off, int len)
                throws TTransportException
        {
            transport.write(bytes, off, len);
        }

        @Override
        public void flush()
                throws TTransportException
        {
            transport.flush();
        }
    }
}
