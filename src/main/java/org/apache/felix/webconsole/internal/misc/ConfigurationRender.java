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
package org.apache.felix.webconsole.internal.misc;


import java.io.*;
import java.net.URL;
import java.text.*;
import java.util.*;
import java.util.zip.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.felix.webconsole.*;
import org.apache.felix.webconsole.internal.OsgiManagerPlugin;
import org.apache.felix.webconsole.internal.i18n.ResourceBundleManager;
import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;


/**
 * ConfigurationRender plugin renders the configuration status - a textual
 * representation of the current framework status. The content itself is
 *  internally generated by the {@link ConfigurationPrinter} plugins.
 */
public class ConfigurationRender extends SimpleWebConsolePlugin implements OsgiManagerPlugin
{

    private static final String LABEL = "config";
    private static final String TITLE = "%configStatus.pluginTitle";
    private static final String[] CSS_REFS = { "/res/ui/configurationrender.css" };

    // use English as the locale for all non-display titles
    private static final Locale DEFAULT = Locale.ENGLISH;

    /**
     * Formatter pattern to generate a relative path for the generation
     * of the plain text or zip file representation of the status. The file
     * name consists of a base name and the current time of status generation.
     */
    private static final SimpleDateFormat FILE_NAME_FORMAT = new SimpleDateFormat( "'" + LABEL
        + "/configuration-status-'yyyyMMdd'-'HHmmZ" );

    /**
     * Formatter pattern to render the current time of status generation.
     */
    private static final DateFormat DISPLAY_DATE_FORMAT = DateFormat.getDateTimeInstance( DateFormat.LONG,
        DateFormat.LONG, Locale.US );

    /**
     * The resource bundle manager to allow for status printer title
     * localization
     */
    private final ResourceBundleManager resourceBundleManager;

    private ServiceTracker cfgPrinterTracker;

    private int cfgPrinterTrackerCount;

    private ArrayList configurationPrinters;

    /** Default constructor */
    public ConfigurationRender( final ResourceBundleManager resourceBundleManager )
    {
        super( LABEL, TITLE, CSS_REFS );
        this.resourceBundleManager = resourceBundleManager;
    }


    /**
     * @see org.apache.felix.webconsole.SimpleWebConsolePlugin#deactivate()
     */
    public void deactivate()
    {
        // make sure the service tracker is closed and removed on deactivate
        ServiceTracker oldTracker = cfgPrinterTracker;
        if ( oldTracker != null )
        {
            oldTracker.close();
        }
        cfgPrinterTracker = null;
        configurationPrinters = null;

        super.deactivate();
    }

    /**
     * Returns the requested printer name if the current request contains one
     */
    private String getRequestedPrinterName(final HttpServletRequest request)
    {
        String name = request.getPathInfo();
        final int dotPos = name.lastIndexOf('.');
        if ( dotPos != -1 )
        {
            name = name.substring(0, dotPos);
        }
        name = name.substring( name.lastIndexOf('/') + 1);
        name = WebConsoleUtil.urlDecode( name );
        return name;
    }
    /**
     * @see org.apache.felix.webconsole.AbstractWebConsolePlugin#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    protected final void doGet( HttpServletRequest request, HttpServletResponse response ) throws ServletException,
        IOException
    {
        if ( request.getPathInfo().endsWith( ".txt" ) )
        {
            response.setContentType( "text/plain; charset=utf-8" );
            ConfigurationWriter pw = new PlainTextConfigurationWriter( response.getWriter() );
            printConfigurationStatus( pw, ConfigurationPrinter.MODE_TXT, getRequestedPrinterName(request) );
            pw.flush();
        }
        else if ( request.getPathInfo().endsWith( ".zip" ) )
        {
            String type = getServletContext().getMimeType( request.getPathInfo() );
            if ( type == null )
            {
                type = "application/x-zip";
            }
            response.setContentType( type );

            ZipOutputStream zip = new ZipOutputStream( response.getOutputStream() );
            zip.setLevel( Deflater.BEST_SPEED );
            zip.setMethod( ZipOutputStream.DEFLATED );

            final ConfigurationWriter pw = new ZipConfigurationWriter( zip );
            printConfigurationStatus( pw, ConfigurationPrinter.MODE_ZIP, getRequestedPrinterName(request) );
            pw.flush();

            addAttachments( pw, ConfigurationPrinter.MODE_ZIP );
            zip.finish();
        }
        else if ( request.getPathInfo().endsWith( ".nfo" ) )
        {
            WebConsoleUtil.setNoCache( response );
            response.setContentType( "text/html; charset=utf-8" );

            final String name = getRequestedPrinterName(request);

            HtmlConfigurationWriter pw = new HtmlConfigurationWriter( response.getWriter() );
            pw.println ( "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\"" );
            pw.println ( "  \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">" );
            pw.println ( "<html xmlns=\"http://www.w3.org/1999/xhtml\">" );
            pw.println ( "<head><title>dummy</title></head><body><div>" );

            Collection printers = getPrintersForLabel(name);
            if ( printers != null )
            {
                for (Iterator i = printers.iterator(); i.hasNext();)
                {
                    final ConfigurationPrinterAdapter desc = (ConfigurationPrinterAdapter) i.next();
                    pw.enableFilter( desc.escapeHtml() );
                    printConfigurationPrinter( pw, desc, ConfigurationPrinter.MODE_WEB );
                    pw.enableFilter( false );
                    pw.println( "</div></body></html>" );
                    return;
                }
            }

            response.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Invalid configuration printer: " + name);
        }
        else
        {
            super.doGet( request, response );
        }
    }


    /**
     * @see org.apache.felix.webconsole.AbstractWebConsolePlugin#renderContent(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    protected final void renderContent( HttpServletRequest request, HttpServletResponse response ) throws IOException
    {

        //ConfigurationWriter pw = new HtmlConfigurationWriter( response.getWriter() );
        PrintWriter pw = response.getWriter();
        pw.println( "<script type='text/javascript' src='${appRoot}/res/ui/ui.tabs.paging.js'></script>" );
        pw.println( "<script type='text/javascript' src='${appRoot}/res/ui/configurationrender.js'></script>" );

        pw.println( "<br/><p class=\"statline\">");

        final Date currentTime = new Date();
        synchronized ( DISPLAY_DATE_FORMAT )
        {
            pw.print("Date: ");
            pw.println(DISPLAY_DATE_FORMAT.format(currentTime));
        }

        pw.print("<button type=\"button\" class=\"downloadZip\" style=\"float: right; margin-right: 30px; margin-top: 5px;\">Download Zip</button>");
        pw.print("<button type=\"button\" class=\"downloadTxt\" style=\"float: right; margin-right: 30px; margin-top: 5px;\">Download Text</button>");
/*        synchronized ( FILE_NAME_FORMAT )
        {
            String fileName = FILE_NAME_FORMAT.format( currentTime );
            pw.print("<br/>Download as <a href='");
            pw.print(fileName);
            pw.print(".txt'>[Single File]</a> or as <a href='");
            pw.print(fileName);
            pw.println(".zip'>[ZIP]</a>");
        }*/

        pw.println("<br/>&nbsp;</p>"); // status line

        // display some information while the data is loading
        // load the data (hidden to begin with)
        pw.println("<div id='tabs'> <!-- tabs container -->");
        pw.println("<ul> <!-- tabs on top -->");

        // print headers only
        final String pluginRoot = request.getAttribute( WebConsoleConstants.ATTR_PLUGIN_ROOT ) + "/";
        Collection printers = getConfigurationPrinters();
        for (Iterator i = printers.iterator(); i.hasNext();)
        {
            final ConfigurationPrinterAdapter desc = (ConfigurationPrinterAdapter) i.next();
            if ( desc.match( ConfigurationPrinter.MODE_WEB ) )
            {
                final String label = desc.label;
                final String title = desc.title;
                pw.print("<li><a href='" + pluginRoot + label + ".nfo'>" + title + "</a></li>" );
            }
        }
        pw.println("</ul> <!-- end tabs on top -->");
        pw.println();

        pw.println("</div> <!-- end tabs container -->");

        pw.println("<div id=\"waitDlg\" title=\"${configStatus.wait}\" class=\"ui-helper-hidden\"><img src=\"${appRoot}/res/imgs/loading.gif\" alt=\"${configStatus.wait}\" />${configStatus.wait.msg}</div>");

        pw.flush();
    }

    private List getPrintersForLabel(final String label)
    {
        List list = null;
        for ( Iterator cpi = getConfigurationPrinters().iterator(); cpi.hasNext(); )
        {
            final ConfigurationPrinterAdapter desc = (ConfigurationPrinterAdapter) cpi.next();
            if (desc.label.equals( label ) )
            {
                if ( list == null )
                {
                    list = new ArrayList();
                    list.add(desc);
                }
            }
        }
        return list;
    }

    private void printConfigurationStatus( ConfigurationWriter pw, final String mode, final String optionalLabel )
    {
        // check if we have printers for that label
        Collection printers = getPrintersForLabel(optionalLabel);
        if ( printers == null )
        {
            // if not use all
            printers = getConfigurationPrinters();
        }

        for ( Iterator cpi = printers.iterator(); cpi.hasNext(); )
        {
            final ConfigurationPrinterAdapter desc = (ConfigurationPrinterAdapter) cpi.next();
            if ( desc.match(mode) )
            {
                printConfigurationPrinter( pw, desc, mode );
            }
        }
    }

    private final synchronized List getConfigurationPrinters()
    {
        if ( cfgPrinterTracker == null )
        {
            try
            {
                cfgPrinterTracker = new ServiceTracker( getBundleContext(),
                        getBundleContext().createFilter("(|(" + Constants.OBJECTCLASS + "=" + ConfigurationPrinter.class.getName() + ")" +
                                "(&(" + WebConsoleConstants.PLUGIN_LABEL + "=*)(&("
                                + WebConsoleConstants.PLUGIN_TITLE + "=*)("
                                + WebConsoleConstants.CONFIG_PRINTER_MODES + "=*))))"),
                        null );
            }
            catch (InvalidSyntaxException e)
            {
                // ignore
            }
            cfgPrinterTracker.open();
            cfgPrinterTrackerCount = -1;
        }

        if ( cfgPrinterTrackerCount != cfgPrinterTracker.getTrackingCount() )
        {
            SortedMap cp = new TreeMap();
            ServiceReference[] refs = cfgPrinterTracker.getServiceReferences();
            if ( refs != null )
            {
                for ( int i = 0; i < refs.length; i++ )
                {
                    final ServiceReference ref = refs[i];
                    final Object service = cfgPrinterTracker.getService( ref );
                    if ( service != null )
                    {
                        final ConfigurationPrinterAdapter desc = ConfigurationPrinterAdapter.createAdapter(service, ref);
                        if ( desc != null )
                        {
                            addConfigurationPrinter( cp, desc, ref.getBundle() );
                        }
                    }
                }
            }
            configurationPrinters = new ArrayList(cp.values());
            cfgPrinterTrackerCount = cfgPrinterTracker.getTrackingCount();
        }

        return configurationPrinters;
    }


    private final void addConfigurationPrinter( final SortedMap printers,
            final ConfigurationPrinterAdapter desc,
            final Bundle provider)
    {
        desc.title = getTitle(desc.title, provider );
        String sortKey = desc.title;
        if ( printers.containsKey( sortKey ) )
        {
            int idx = -1;
            String idxTitle;
            do
            {
                idx++;
                idxTitle = sortKey + idx;
            }
            while ( printers.containsKey( idxTitle ) );
            sortKey = idxTitle;
        }
        if ( desc.label == null )
        {
            desc.label = sortKey;
        }
        printers.put( sortKey, desc );
    }


    // This is Sling stuff, we comment it out for now
    //    private void printRawFrameworkProperties(PrintWriter pw) {
    //        pw.println("*** Raw Framework properties:");
    //
    //        File file = new File(getBundleContext().getProperty("sling.home"),
    //            "sling.properties");
    //        if (file.exists()) {
    //            Properties props = new Properties();
    //            InputStream ins = null;
    //            try {
    //                ins = new FileInputStream(file);
    //                props.load(ins);
    //            } catch (IOException ioe) {
    //                // handle or ignore
    //            } finally {
    //                IOUtils.closeQuietly(ins);
    //            }
    //
    //            SortedSet keys = new TreeSet(props.keySet());
    //            for (Iterator ki = keys.iterator(); ki.hasNext();) {
    //                Object key = ki.next();
    //                infoLine(pw, null, (String) key, props.get(key));
    //            }
    //
    //        } else {
    //            pw.println("  No Framework properties in " + file);
    //        }
    //
    //        pw.println();
    //    }


    private final void printConfigurationPrinter( final ConfigurationWriter pw,
            final ConfigurationPrinterAdapter desc,
            final String mode )
    {
        pw.title( desc.title );
        try
        {
            desc.printConfiguration(pw, mode);
        }
        catch ( Throwable t )
        {
            pw.println();
            pw.println( "Configuration Printer failed: " + t.toString() );
            pw.println();
            log( "Configuration Printer " + desc + " failed", t );
        }
        pw.end();
    }


    /**
     * Renders an info line - element in the framework configuration. The info line will
     * look like:
     * <pre>
     * label = value
     * </pre>
     *
     * Optionally it can be indented by a specific string.
     *
     * @param pw the writer to print to
     * @param indent indentation string
     * @param label the label data
     * @param value the data itself.
     */
    public static final void infoLine( PrintWriter pw, String indent, String label, Object value )
    {
        if ( indent != null )
        {
            pw.print( indent );
        }

        if ( label != null )
        {
            pw.print( label );
            pw.print( " = " );
        }

        pw.print( WebConsoleUtil.toString( value ) );

        pw.println();
    }

    private final String getTitle( final String title, final Bundle provider )
    {
        if ( !title.startsWith( "%" ) )
        {
            return title;
        }

        ResourceBundle res = resourceBundleManager.getResourceBundle( provider, DEFAULT );
        return res.getString( title.substring( 1 ) );
    }

    private abstract static class ConfigurationWriter extends PrintWriter
    {

        ConfigurationWriter( Writer delegatee )
        {
            super( delegatee );
        }


        abstract void title( String title );


        abstract void end();


        public void handleAttachments( final String title, final URL[] urls ) throws IOException
        {
            throw new UnsupportedOperationException( "handleAttachments not supported by this configuration writer: "
                + this );
        }

    }

    private static class HtmlConfigurationWriter extends ConfigurationWriter
    {

        // whether or not to filter "<" signs in the output
        private boolean doFilter;


        HtmlConfigurationWriter( Writer delegatee )
        {
            super( delegatee );
        }


        void enableFilter( final boolean doFilter )
        {
            this.doFilter = doFilter;
        }


        public void title( String title )
        {
        }


        public void end()
        {
        }


        // IE has an issue with white-space:pre in our case so, we write
        // <br/> instead of [CR]LF to get the line break. This also works
        // in other browsers.
        public void println()
        {
            if ( doFilter )
            {
                this.write('\n'); // write <br/>
            }
            else
            {
                super.println();
            }
        }

        // some VM implementation directly write in underlying stream, instead of
        // delegation to the write() method. So we need to override this, to make
        // sure, that everything is escaped correctly
        public void print(String str)
        {
            final char[] chars = str.toCharArray();
            write(chars, 0, chars.length);
        }


        private final char[] oneChar = new char[1];

        // always delegate to write(char[], int, int) otherwise in some VM
        // it cause endless cycle and StackOverflowError
        public void write(final int character)
        {
            synchronized (oneChar)
            {
                oneChar[0] = (char) character;
                write(oneChar, 0, 1);
            }
        }

        // write the characters unmodified unless filtering is enabled in
        // which case the writeFiltered(String) method is called for filtering
        public void write(char[] chars, int off, int len)
        {
            if (doFilter)
            {
                chars = WebConsoleUtil.escapeHtml(new String(chars, off, len)).toCharArray();
                off = 0;
                len = chars.length;
            }
            super.write(chars, off, len);
        }


        // write the string unmodified unless filtering is enabled in
        // which case the writeFiltered(String) method is called for filtering
        public void write( final String string, final int off, final int len )
        {
            write(string.toCharArray(), off, len);
        }

    }

    private void addAttachments( final ConfigurationWriter cf, final String mode )
    throws IOException
    {
        for ( Iterator cpi = getConfigurationPrinters().iterator(); cpi.hasNext(); )
        {
            // check if printer supports zip mode
            final ConfigurationPrinterAdapter desc = (ConfigurationPrinterAdapter) cpi.next();
            if ( desc.match(mode) )
            {
                final URL[] attachments = desc.getAttachments(mode);
                if ( attachments != null )
                {
                    cf.handleAttachments( desc.title, attachments );
                }
            }
        }

    }

    private static class PlainTextConfigurationWriter extends ConfigurationWriter
    {

        PlainTextConfigurationWriter( Writer delegatee )
        {
            super( delegatee );
        }


        public void title( String title )
        {
            print( "*** " );
            print( title );
            println( ":" );
        }


        public void end()
        {
            println();
        }
    }

    private static class ZipConfigurationWriter extends ConfigurationWriter
    {
        private final ZipOutputStream zip;

        private int counter;


        ZipConfigurationWriter( ZipOutputStream zip )
        {
            super( new OutputStreamWriter( zip ) );
            this.zip = zip;
        }


        public void title( String title )
        {
            String name = MessageFormat.format( "{0,number,000}-{1}.txt", new Object[]
                { new Integer( counter ), title } );

            counter++;

            ZipEntry entry = new ZipEntry( name );
            try
            {
                zip.putNextEntry( entry );
            }
            catch ( IOException ioe )
            {
                // should handle
            }
        }

        private OutputStream startFile( String title, String name)
        {
            final String path = MessageFormat.format( "{0,number,000}-{1}/{2}", new Object[]
                 { new Integer( counter ), title, name } );
            ZipEntry entry = new ZipEntry( path );
            try
            {
                zip.putNextEntry( entry );
            }
            catch ( IOException ioe )
            {
                // should handle
            }
            return zip;
        }

        public void handleAttachments( final String title, final URL[] attachments)
        throws IOException
        {
            for(int i = 0; i < attachments.length; i++)
            {
                final URL current = attachments[i];
                final String path = current.getPath();
                final String name;
                if ( path == null || path.length() == 0 )
                {
                    // sanity code, we should have a path, but if not let's
                    // just create some random name
                    name = "file" + Double.doubleToLongBits( Math.random() );
                }
                else
                {
                    final int pos = path.lastIndexOf('/');
                    name = (pos == -1 ? path : path.substring(pos + 1));
                }
                final OutputStream os = this.startFile(title, name);
                final InputStream is = current.openStream();
                try
                {
                    IOUtils.copy(is, os);
                }
                finally
                {
                    IOUtils.closeQuietly(is);
                }
                this.end();
            }

            // increase the filename counter
            counter++;
        }


        public void end()
        {
            flush();

            try
            {
                zip.closeEntry();
            }
            catch ( IOException ioe )
            {
                // should handle
            }
        }
    }
}
