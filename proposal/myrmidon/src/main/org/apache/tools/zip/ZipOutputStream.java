/*
 * Copyright (C) The Apache Software Foundation. All rights reserved.
 *
 * This software is published under the terms of the Apache Software License
 * version 1.1, a copy of which has been included with this distribution in
 * the LICENSE file.
 */
package org.apache.tools.zip;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipException;

/**
 * Reimplementation of {@link java.util.zip.ZipOutputStream
 * java.util.zip.ZipOutputStream} that does handle the extended functionality of
 * this package, especially internal/external file attributes and extra fields
 * with different layouts for local file data and central directory entries. <p>
 *
 * This implementation will use a Data Descriptor to store size and CRC
 * information for DEFLATED entries, this means, you don't need to calculate
 * them yourself. Unfortunately this is not possible for the STORED method, here
 * setting the CRC and uncompressed size information is required before {@link
 * #putNextEntry putNextEntry} will be called.</p>
 *
 * @author <a href="stefan.bodewig@epost.de">Stefan Bodewig</a>
 * @version $Revision$
 */
public class ZipOutputStream extends DeflaterOutputStream
{

    /**
     * Helper, a 0 as ZipShort.
     *
     * @since 1.1
     */
    private final static byte[] ZERO = {0, 0};

    /**
     * Helper, a 0 as ZipLong.
     *
     * @since 1.1
     */
    private final static byte[] LZERO = {0, 0, 0, 0};

    /**
     * Compression method for deflated entries.
     *
     * @since 1.1
     */
    public final static int DEFLATED = ZipEntry.DEFLATED;

    /**
     * Compression method for deflated entries.
     *
     * @since 1.1
     */
    public final static int STORED = ZipEntry.STORED;

    /*
     * Various ZIP constants
     */
    /**
     * local file header signature
     *
     * @since 1.1
     */
    protected final static ZipLong LFH_SIG = new ZipLong( 0X04034B50L );
    /**
     * data descriptor signature
     *
     * @since 1.1
     */
    protected final static ZipLong DD_SIG = new ZipLong( 0X08074B50L );
    /**
     * central file header signature
     *
     * @since 1.1
     */
    protected final static ZipLong CFH_SIG = new ZipLong( 0X02014B50L );
    /**
     * end of central dir signature
     *
     * @since 1.1
     */
    protected final static ZipLong EOCD_SIG = new ZipLong( 0X06054B50L );

    /**
     * Smallest date/time ZIP can handle.
     *
     * @since 1.1
     */
    private final static ZipLong DOS_TIME_MIN = new ZipLong( 0x00002100L );

    /**
     * The file comment.
     *
     * @since 1.1
     */
    private String comment = "";

    /**
     * Compression level for next entry.
     *
     * @since 1.1
     */
    private int level = Deflater.DEFAULT_COMPRESSION;

    /**
     * Default compression method for next entry.
     *
     * @since 1.1
     */
    private int method = DEFLATED;

    /**
     * List of ZipEntries written so far.
     *
     * @since 1.1
     */
    private ArrayList entries = new ArrayList();

    /**
     * CRC instance to avoid parsing DEFLATED data twice.
     *
     * @since 1.1
     */
    private CRC32 crc = new CRC32();

    /**
     * Count the bytes written to out.
     *
     * @since 1.1
     */
    private long written = 0;

    /**
     * Data for current entry started here.
     *
     * @since 1.1
     */
    private long dataStart = 0;

    /**
     * Start of central directory.
     *
     * @since 1.1
     */
    private ZipLong cdOffset = new ZipLong( 0 );

    /**
     * Length of central directory.
     *
     * @since 1.1
     */
    private ZipLong cdLength = new ZipLong( 0 );

    /**
     * Holds the offsets of the LFH starts for each entry
     *
     * @since 1.1
     */
    private Hashtable offsets = new Hashtable();

    /**
     * The encoding to use for filenames and the file comment. <p>
     *
     * For a list of possible values see <a
     * href="http://java.sun.com/products/jdk/1.2/docs/guide/internat/encoding.doc.html">
     * http://java.sun.com/products/jdk/1.2/docs/guide/internat/encoding.doc.html
     * </a>. Defaults to the platform's default character encoding.</p>
     *
     * @since 1.3
     */
    private String encoding = null;

    /**
     * Current entry.
     *
     * @since 1.1
     */
    private ZipEntry entry;

    /**
     * Creates a new ZIP OutputStream filtering the underlying stream.
     *
     * @param out Description of Parameter
     * @since 1.1
     */
    public ZipOutputStream( OutputStream out )
    {
        super( out, new Deflater( Deflater.DEFAULT_COMPRESSION, true ) );
    }

    /**
     * Convert a Date object to a DOS date/time field. <p>
     *
     * Stolen from InfoZip's <code>fileio.c</code></p>
     *
     * @param time Description of Parameter
     * @return Description of the Returned Value
     * @since 1.1
     */
    protected static ZipLong toDosTime( Date time )
    {
        int year = time.getYear() + 1900;
        int month = time.getMonth() + 1;
        if( year < 1980 )
        {
            return DOS_TIME_MIN;
        }
        long value = ( ( year - 1980 ) << 25 )
            | ( month << 21 )
            | ( time.getDate() << 16 )
            | ( time.getHours() << 11 )
            | ( time.getMinutes() << 5 )
            | ( time.getSeconds() >> 1 );

        byte[] result = new byte[ 4 ];
        result[ 0 ] = (byte)( ( value & 0xFF ) );
        result[ 1 ] = (byte)( ( value & 0xFF00 ) >> 8 );
        result[ 2 ] = (byte)( ( value & 0xFF0000 ) >> 16 );
        result[ 3 ] = (byte)( ( value & 0xFF000000l ) >> 24 );
        return new ZipLong( result );
    }

    /**
     * Set the file comment.
     *
     * @param comment The new Comment value
     * @since 1.1
     */
    public void setComment( String comment )
    {
        this.comment = comment;
    }

    /**
     * The encoding to use for filenames and the file comment. <p>
     *
     * For a list of possible values see <a
     * href="http://java.sun.com/products/jdk/1.2/docs/guide/internat/encoding.doc.html">
     * http://java.sun.com/products/jdk/1.2/docs/guide/internat/encoding.doc.html
     * </a>. Defaults to the platform's default character encoding.</p>
     *
     * @param encoding The new Encoding value
     * @since 1.3
     */
    public void setEncoding( String encoding )
    {
        this.encoding = encoding;
    }

    /**
     * Sets the compression level for subsequent entries. <p>
     *
     * Default is Deflater.DEFAULT_COMPRESSION.</p>
     *
     * @param level The new Level value
     * @since 1.1
     */
    public void setLevel( int level )
    {
        this.level = level;
    }

    /**
     * Sets the default compression method for subsequent entries. <p>
     *
     * Default is DEFLATED.</p>
     *
     * @param method The new Method value
     * @since 1.1
     */
    public void setMethod( int method )
    {
        this.method = method;
    }

    /**
     * The encoding to use for filenames and the file comment.
     *
     * @return null if using the platform's default character encoding.
     * @since 1.3
     */
    public String getEncoding()
    {
        return encoding;
    }

    /**
     * Writes all necessary data for this entry.
     *
     * @exception IOException Description of Exception
     * @since 1.1
     */
    public void closeEntry()
        throws IOException
    {
        if( entry == null )
        {
            return;
        }

        long realCrc = crc.getValue();
        crc.reset();

        if( entry.getMethod() == DEFLATED )
        {
            def.finish();
            while( !def.finished() )
            {
                deflate();
            }

            entry.setSize( def.getTotalIn() );
            entry.setComprSize( def.getTotalOut() );
            entry.setCrc( realCrc );

            def.reset();

            written += entry.getCompressedSize();
        }
        else
        {
            if( entry.getCrc() != realCrc )
            {
                throw new ZipException( "bad CRC checksum for entry "
                                        + entry.getName() + ": "
                                        + Long.toHexString( entry.getCrc() )
                                        + " instead of "
                                        + Long.toHexString( realCrc ) );
            }

            if( entry.getSize() != written - dataStart )
            {
                throw new ZipException( "bad size for entry "
                                        + entry.getName() + ": "
                                        + entry.getSize()
                                        + " instead of "
                                        + ( written - dataStart ) );
            }

        }

        writeDataDescriptor( entry );
        entry = null;
    }

    /*
     * Found out by experiment, that DeflaterOutputStream.close()
     * will call finish() - so we don't need to override close
     * ourselves.
     */
    /**
     * Finishs writing the contents and closes this as well as the underlying
     * stream.
     *
     * @exception IOException Description of Exception
     * @since 1.1
     */
    public void finish()
        throws IOException
    {
        closeEntry();
        cdOffset = new ZipLong( written );
        for( int i = 0; i < entries.size(); i++ )
        {
            writeCentralFileHeader( (ZipEntry)entries.get( i ) );
        }
        cdLength = new ZipLong( written - cdOffset.getValue() );
        writeCentralDirectoryEnd();
        offsets.clear();
        entries.clear();
    }

    /**
     * Begin writing next entry.
     *
     * @param ze Description of Parameter
     * @exception IOException Description of Exception
     * @since 1.1
     */
    public void putNextEntry( ZipEntry ze )
        throws IOException
    {
        closeEntry();

        entry = ze;
        entries.add( entry );

        if( entry.getMethod() == -1 )
        {// not specified
            entry.setMethod( method );
        }

        if( entry.getTime() == -1 )
        {// not specified
            entry.setTime( System.currentTimeMillis() );
        }

        if( entry.getMethod() == STORED )
        {
            if( entry.getSize() == -1 )
            {
                throw new ZipException( "uncompressed size is required for STORED method" );
            }
            if( entry.getCrc() == -1 )
            {
                throw new ZipException( "crc checksum is required for STORED method" );
            }
            entry.setComprSize( entry.getSize() );
        }
        else
        {
            def.setLevel( level );
        }
        writeLocalFileHeader( entry );
    }

    /**
     * Writes bytes to ZIP entry. <p>
     *
     * Override is necessary to support STORED entries, as well as calculationg
     * CRC automatically for DEFLATED entries.</p>
     *
     * @param b Description of Parameter
     * @param offset Description of Parameter
     * @param length Description of Parameter
     * @exception IOException Description of Exception
     */
    public void write( byte[] b, int offset, int length )
        throws IOException
    {
        if( entry.getMethod() == DEFLATED )
        {
            super.write( b, offset, length );
        }
        else
        {
            out.write( b, offset, length );
            written += length;
        }
        crc.update( b, offset, length );
    }

    /**
     * Retrieve the bytes for the given String in the encoding set for this
     * Stream.
     *
     * @param name Description of Parameter
     * @return The Bytes value
     * @exception ZipException Description of Exception
     * @since 1.3
     */
    protected byte[] getBytes( String name )
        throws ZipException
    {
        if( encoding == null )
        {
            return name.getBytes();
        }
        else
        {
            try
            {
                return name.getBytes( encoding );
            }
            catch( UnsupportedEncodingException uee )
            {
                throw new ZipException( uee.getMessage() );
            }
        }
    }

    /**
     * Writes the &quot;End of central dir record&quot;
     *
     * @exception IOException Description of Exception
     * @since 1.1
     */
    protected void writeCentralDirectoryEnd()
        throws IOException
    {
        out.write( EOCD_SIG.getBytes() );

        // disk numbers
        out.write( ZERO );
        out.write( ZERO );

        // number of entries
        byte[] num = ( new ZipShort( entries.size() ) ).getBytes();
        out.write( num );
        out.write( num );

        // length and location of CD
        out.write( cdLength.getBytes() );
        out.write( cdOffset.getBytes() );

        // ZIP file comment
        byte[] data = getBytes( comment );
        out.write( ( new ZipShort( data.length ) ).getBytes() );
        out.write( data );
    }

    /**
     * Writes the central file header entry
     *
     * @param ze Description of Parameter
     * @exception IOException Description of Exception
     * @since 1.1
     */
    protected void writeCentralFileHeader( ZipEntry ze )
        throws IOException
    {
        out.write( CFH_SIG.getBytes() );
        written += 4;

        // version made by
        out.write( ( new ZipShort( 20 ) ).getBytes() );
        written += 2;

        // version needed to extract
        // general purpose bit flag
        if( ze.getMethod() == DEFLATED )
        {
            // requires version 2 as we are going to store length info
            // in the data descriptor
            out.write( ( new ZipShort( 20 ) ).getBytes() );

            // bit3 set to signal, we use a data descriptor
            out.write( ( new ZipShort( 8 ) ).getBytes() );
        }
        else
        {
            out.write( ( new ZipShort( 10 ) ).getBytes() );
            out.write( ZERO );
        }
        written += 4;

        // compression method
        out.write( ( new ZipShort( ze.getMethod() ) ).getBytes() );
        written += 2;

        // last mod. time and date
        out.write( toDosTime( new Date( ze.getTime() ) ).getBytes() );
        written += 4;

        // CRC
        // compressed length
        // uncompressed length
        out.write( ( new ZipLong( ze.getCrc() ) ).getBytes() );
        out.write( ( new ZipLong( ze.getCompressedSize() ) ).getBytes() );
        out.write( ( new ZipLong( ze.getSize() ) ).getBytes() );
        written += 12;

        // file name length
        byte[] name = getBytes( ze.getName() );
        out.write( ( new ZipShort( name.length ) ).getBytes() );
        written += 2;

        // extra field length
        byte[] extra = ze.getCentralDirectoryExtra();
        out.write( ( new ZipShort( extra.length ) ).getBytes() );
        written += 2;

        // file comment length
        String comm = ze.getComment();
        if( comm == null )
        {
            comm = "";
        }
        byte[] comment = getBytes( comm );
        out.write( ( new ZipShort( comment.length ) ).getBytes() );
        written += 2;

        // disk number start
        out.write( ZERO );
        written += 2;

        // internal file attributes
        out.write( ( new ZipShort( ze.getInternalAttributes() ) ).getBytes() );
        written += 2;

        // external file attributes
        out.write( ( new ZipLong( ze.getExternalAttributes() ) ).getBytes() );
        written += 4;

        // relative offset of LFH
        out.write( ( (ZipLong)offsets.get( ze ) ).getBytes() );
        written += 4;

        // file name
        out.write( name );
        written += name.length;

        // extra field
        out.write( extra );
        written += extra.length;

        // file comment
        out.write( comment );
        written += comment.length;
    }

    /**
     * Writes the data descriptor entry
     *
     * @param ze Description of Parameter
     * @exception IOException Description of Exception
     * @since 1.1
     */
    protected void writeDataDescriptor( ZipEntry ze )
        throws IOException
    {
        if( ze.getMethod() != DEFLATED )
        {
            return;
        }
        out.write( DD_SIG.getBytes() );
        out.write( ( new ZipLong( entry.getCrc() ) ).getBytes() );
        out.write( ( new ZipLong( entry.getCompressedSize() ) ).getBytes() );
        out.write( ( new ZipLong( entry.getSize() ) ).getBytes() );
        written += 16;
    }

    /**
     * Writes the local file header entry
     *
     * @param ze Description of Parameter
     * @exception IOException Description of Exception
     * @since 1.1
     */
    protected void writeLocalFileHeader( ZipEntry ze )
        throws IOException
    {
        offsets.put( ze, new ZipLong( written ) );

        out.write( LFH_SIG.getBytes() );
        written += 4;

        // version needed to extract
        // general purpose bit flag
        if( ze.getMethod() == DEFLATED )
        {
            // requires version 2 as we are going to store length info
            // in the data descriptor
            out.write( ( new ZipShort( 20 ) ).getBytes() );

            // bit3 set to signal, we use a data descriptor
            out.write( ( new ZipShort( 8 ) ).getBytes() );
        }
        else
        {
            out.write( ( new ZipShort( 10 ) ).getBytes() );
            out.write( ZERO );
        }
        written += 4;

        // compression method
        out.write( ( new ZipShort( ze.getMethod() ) ).getBytes() );
        written += 2;

        // last mod. time and date
        out.write( toDosTime( new Date( ze.getTime() ) ).getBytes() );
        written += 4;

        // CRC
        // compressed length
        // uncompressed length
        if( ze.getMethod() == DEFLATED )
        {
            out.write( LZERO );
            out.write( LZERO );
            out.write( LZERO );
        }
        else
        {
            out.write( ( new ZipLong( ze.getCrc() ) ).getBytes() );
            out.write( ( new ZipLong( ze.getSize() ) ).getBytes() );
            out.write( ( new ZipLong( ze.getSize() ) ).getBytes() );
        }
        written += 12;

        // file name length
        byte[] name = getBytes( ze.getName() );
        out.write( ( new ZipShort( name.length ) ).getBytes() );
        written += 2;

        // extra field length
        byte[] extra = ze.getLocalFileDataExtra();
        out.write( ( new ZipShort( extra.length ) ).getBytes() );
        written += 2;

        // file name
        out.write( name );
        written += name.length;

        // extra field
        out.write( extra );
        written += extra.length;

        dataStart = written;
    }

}
