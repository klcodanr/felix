/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.gogo.runtime;

import java.io.EOFException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.felix.gogo.runtime.Parser.Array;
import org.apache.felix.gogo.runtime.Parser.Executable;
import org.apache.felix.gogo.runtime.Parser.Pipeline;
import org.apache.felix.gogo.runtime.Parser.Program;
import org.apache.felix.gogo.runtime.Parser.Sequence;
import org.apache.felix.gogo.runtime.Parser.Statement;
import org.apache.felix.service.command.CommandSession;
import org.apache.felix.service.command.Function;

public class Closure implements Function, Evaluate
{

    public static final String LOCATION = ".location";
    private static final String DEFAULT_LOCK = ".defaultLock";

    private static final ThreadLocal<String> location = new ThreadLocal<String>();

    private final CommandSessionImpl session;
    private final Closure parent;
    private final CharSequence source;
    private final Program program;
    private final Object script;

    private Token errTok;
    private Token errTok2;
    private List<Object> parms = null;
    private List<Object> parmv = null;

    public Closure(CommandSessionImpl session, Closure parent, CharSequence source) throws Exception
    {
        this.session = session;
        this.parent = parent;
        this.source = source;
        this.script = session.get("0"); // by convention, $0 is script name

        if (source instanceof Program)
        {
            program = (Program) source;
        }
        else
        {
            try
            {
                this.program = new Parser(source).program();
            }
            catch (Exception e)
            {
                throw setLocation(e);
            }
        }
    }

    public Closure(CommandSessionImpl session, Closure parent, Program program)
    {
        this.session = session;
        this.parent = parent;
        this.source = program;
        this.script = session.get("0"); // by convention, $0 is script name
        this.program = program;
    }

    public CommandSessionImpl session()
    {
        return session;
    }

    private Exception setLocation(Exception e)
    {
        if (session.get(DEFAULT_LOCK) == null)
        {
            String loc = location.get();
            if (null == loc)
            {
                loc = (null == script ? "" : script + ":");

                if (e instanceof SyntaxError)
                {
                    SyntaxError se = (SyntaxError) e;
                    loc += se.line() + "." + se.column();
                }
                else if (null != errTok)
                {
                    loc += errTok.line + "." + errTok.column;
                }

                location.set(loc);
            }
            else if (null != script && !loc.contains(":"))
            {
                location.set(script + ":" + loc);
            }

            session.put(LOCATION, location.get());
        }

        if (e instanceof EOFError)
        { // map to public exception, so interactive clients can provide more input
            EOFException eofe = new EOFException(e.getMessage());
            eofe.initCause(e);
            return eofe;
        }

        return e;
    }

    // implements Function interface
    public Object execute(CommandSession x, List<Object> values) throws Exception
    {
        try
        {
            location.remove();
            session.put(LOCATION, null);
            return execute(values);
        }
        catch (Exception e)
        {
            throw setLocation(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object execute(List<Object> values) throws Exception
    {
        if (null != values)
        {
            parmv = values;
            parms = new ArgList(parmv);
        }
        else if (null != parent)
        {
            // inherit parent closure parameters
            parms = parent.parms;
            parmv = parent.parmv;
        }
        else
        {
            // inherit session parameters
            Object args = session.get("args");
            if (null != args && args instanceof List<?>)
            {
                parmv = (List<Object>) args;
                parms = new ArgList(parmv);
            }
        }

        Pipe last = null;
        Object[] mark = Pipe.mark();

        for (Executable executable : program.tokens())
        {
            List<Pipe> pipes = toPipes(executable);

            for (int i = 0; i < pipes.size(); i++)
            {
                Pipe current = pipes.get(i);
                if (i == 0)
                {
                    if (current.out == null)
                    {
                        current.setIn(session.in);
                        current.setOut(session.out);
                        current.setErr(session.err);
                    }
                }
                else
                {
                    Pipe previous = pipes.get(i - 1);
                    previous.connect(current);
                }
            }

            if (pipes.size() == 1)
            {
                pipes.get(0).run();
            }
            else if (pipes.size() > 1)
            {
                for (Pipe pipe : pipes)
                {
                    pipe.start();
                }
                try
                {
                    for (Pipe pipe : pipes)
                    {
                        pipe.join();
                    }
                }
                catch (InterruptedException e)
                {
                    for (Pipe pipe : pipes)
                    {
                        pipe.interrupt();
                    }
                    throw e;
                }
            }

            for (int i = 0; i < pipes.size() - 1; i++)
            {
                Pipe pipe = pipes.get(i);
                if (pipe.exception != null)
                {
                    // can't throw exception, as result is defined by last pipe
                    Object oloc = session.get(LOCATION);
                    String loc = (String.valueOf(oloc).contains(":") ? oloc + ": "
                        : "pipe: ");
                    session.err.println(loc + pipe.exception);
                    session.put("pipe-exception", pipe.exception);
                }
            }
            last = pipes.get(pipes.size() - 1);
            if (last.exception != null)
            {
                Pipe.reset(mark);
                throw last.exception;
            }
        }

        Pipe.reset(mark); // reset IO in case same thread used for new client

        return last == null ? null : last.result;
    }

    private List<Pipe> toPipes(Executable executable)
    {
        if (executable instanceof Pipeline)
        {
            List<Pipe> pipes = new ArrayList<Pipe>();
            Pipeline pipeline = (Pipeline) executable;
            for (Executable ex : pipeline.tokens())
            {
                pipes.add(new Pipe(this, ex));
            }
            return pipes;
        }
        else
        {
            return Collections.singletonList(new Pipe(this, executable));
        }
    }

    private Object eval(Object v)
    {
        String s = v.toString();
        if ("null".equals(s))
        {
            v = null;
        }
        else if ("false".equals(s))
        {
            v = false;
        }
        else if ("true".equals(s))
        {
            v = true;
        }
        else
        {
            try
            {
                v = s;
                v = Double.parseDouble(s);    // if it parses as double
                v = Long.parseLong(s);        // see whether it is integral
            }
            catch (NumberFormatException e)
            {
                // Ignore
            }
        }
        return v;
    }

    public Object eval(final Token t) throws Exception
    {
        if (t instanceof Parser.Closure)
        {
            return new Closure(session, this, ((Parser.Closure) t).program());
        }
        else if (t instanceof Sequence)
        {
            return new Closure(session, this, ((Sequence) t).program())
                    .execute(session, parms);
        }
        else if (t instanceof Array)
        {
            return array((Array) t);
        }
        else {
            Object v = Expander.expand(t, this);
            if (t == v)
            {
                v = eval(v);
            }
            return v;
        }
    }

    public Object execute(Executable executable) throws Exception
    {
        if (executable instanceof Statement)
        {
            return executeStatement((Statement) executable);
        }
        else if (executable instanceof Sequence)
        {
            return new Closure(session, this, ((Sequence) executable).program()).execute(new ArrayList<Object>());
        }
        else
        {
            throw new IllegalStateException();
        }
    }

    /*
     * executeStatement handles the following cases:
     *    <string> '=' word // simple assignment
     *    <string> '=' word word.. // complex assignment
     *    <bareword> word.. // command invocation
     *    <object> // value of <object>
     *    <object> word.. // method call
     */
    public Object executeStatement(Statement statement) throws Exception
    {
        Object echo = session.get("echo");
        String xtrace = null;

        if (echo != null && !"false".equals(echo.toString()))
        {
            // set -x execution trace
            xtrace = "+" + statement;
            session.err.println(xtrace);
        }

        List<Token> tokens = statement.tokens();
        if (tokens.isEmpty())
        {
            return null;
        }

        List<Object> values = new ArrayList<Object>();
        errTok = tokens.get(0);

        if ((tokens.size() > 3) && Token.eq("=", tokens.get(1)))
        {
            errTok2 = tokens.get(2);
        }

        for (Token t : tokens)
        {
            Object v = eval(t);

//            if ((Token.Type.EXECUTION == t.type) && (tokens.size() == 1)) {
//                return v;
//            }

            if (parms == v && parms != null)
            {
                values.addAll(parms); // explode $args array
            }
            else
            {
                values.add(v);
            }
        }

        Object cmd = values.remove(0);
        if (cmd == null)
        {
            if (values.isEmpty())
            {
                return null;
            }

            throw new RuntimeException("Command name evaluates to null: " + errTok);
        }

        if (cmd instanceof CharSequence && values.size() > 0
                && Token.eq("=", tokens.get(1)))
        {
            values.remove(0);
            String scmd = cmd.toString();
            Object value;

            if (values.size() == 0)
            {
                return session.put(scmd, null);
            }

            if (values.size() == 1)
            {
                value = values.get(0);
            }
            else
            {
                cmd = values.remove(0);
                if (null == cmd)
                {
                    throw new RuntimeException("Command name evaluates to null: "
                            + errTok2);
                }

                trace2(xtrace, cmd, values);

                value = bareword(tokens.get(2), cmd) ? executeCmd(cmd.toString(), values)
                    : executeMethod(cmd, values);
            }

            return assignment(scmd, value);
        }

        trace2(xtrace, cmd, values);

        return bareword(tokens.get(0), cmd) ? executeCmd(cmd.toString(), values)
            : executeMethod(cmd, values);
    }

    // second level expanded execution trace
    private void trace2(String trace1, Object cmd, List<Object> values)
    {
        if ("verbose".equals(session.get("echo")))
        {
            StringBuilder buf = new StringBuilder("+ " + cmd);

            for (Object value : values)
            {
                buf.append(' ');
                buf.append(value);
            }

            String trace2 = buf.toString();

            if (!trace2.equals(trace1))
            {
                session.err.println("+" + trace2);
            }
        }
    }

    private boolean bareword(Token t, Object v) throws Exception
    {
        return v instanceof CharSequence && Token.eq(t, (CharSequence) v);
    }

    private Object executeCmd(String scmd, List<Object> values) throws Exception
    {
        String scopedFunction = scmd;
        Object x = get(scmd);

        if (!(x instanceof Function))
        {
            if (scmd.indexOf(':') < 0)
            {
                scopedFunction = "*:" + scmd;
            }

            x = get(scopedFunction);

            if (x == null || !(x instanceof Function))
            {
                // try default command handler
                if (session.get(DEFAULT_LOCK) == null)
                {
                    x = get("default");
                    if (x == null)
                    {
                        x = get("*:default");
                    }

                    if (x instanceof Function)
                    {
                        try
                        {
                            session.put(DEFAULT_LOCK, true);
                            values.add(0, scmd);
                            return ((Function) x).execute(session, values);
                        }
                        finally
                        {
                            session.put(DEFAULT_LOCK, null);
                        }
                    }
                }

                throw new CommandNotFoundException(scmd);
            }
        }
        return ((Function) x).execute(session, values);
    }

    private Object executeMethod(Object cmd, List<Object> values) throws Exception
    {
        if (values.isEmpty())
        {
            return cmd;
        }

        boolean dot = values.size() > 1 && ".".equals(String.valueOf(values.get(0)));

        // FELIX-1473 - allow method chaining using dot pseudo-operator, e.g.
        //  (bundle 0) . loadClass java.net.InetAddress . localhost . hostname
        //  (((bundle 0) loadClass java.net.InetAddress ) localhost ) hostname
        if (dot)
        {
            Object target = cmd;
            ArrayList<Object> args = new ArrayList<Object>();
            values.remove(0);

            for (Object arg : values)
            {
                if (".".equals(arg))
                {
                    target = Reflective.invoke(session, target,
                        args.remove(0).toString(), args);
                    args.clear();
                }
                else
                {
                    args.add(arg);
                }
            }

            if (args.size() == 0)
            {
                return target;
            }

            return Reflective.invoke(session, target, args.remove(0).toString(), args);
        }
        else if (cmd.getClass().isArray() && values.size() == 1)
        {
            Object[] cmdv = (Object[]) cmd;
            String index = values.get(0).toString();
            return "length".equals(index) ? cmdv.length : cmdv[Integer.parseInt(index)];
        }
        else
        {
            return Reflective.invoke(session, cmd, values.remove(0).toString(), values);
        }
    }

    private Object assignment(String name, Object value)
    {
        session.put(name, value);
        return value;
    }

    public Object expr(Token expr)
    {
        return session.expr(expr);
    }

    private Object array(Array array) throws Exception
    {
        List<Token> list = array.list();
        Map<Token, Token> map = array.map();

        if (list != null)
        {
            List<Object> olist = new ArrayList<Object>();
            for (Token t : list)
            {
                Object oval = eval(t);
                if (oval.getClass().isArray())
                {
                    Collections.addAll(olist, (Object[]) oval);
                }
                else
                {
                    olist.add(oval);
                }
            }
            return olist;
        }
        else
        {
            Map<Object, Object> omap = new LinkedHashMap<Object, Object>();
            for (Entry<Token, Token> e : map.entrySet())
            {
                Token key = e.getKey();
                Object k = eval(key);
                if (!(k instanceof String))
                {
                    throw new SyntaxError(key.line(), key.column(),
                        "map key null or not String: " + key);
                }
                omap.put(k, eval(e.getValue()));
            }
            return omap;
        }
    }

    public Object get(String name)
    {
        if (parms != null)
        {
            if ("args".equals(name))
            {
                return parms;
            }

            if ("argv".equals(name))
            {
                return parmv;
            }

            if ("it".equals(name))
            {
                return parms.get(0);
            }

            if (name.length() == 1 && Character.isDigit(name.charAt(0)))
            {
                int i = name.charAt(0) - '0';
                if (i > 0)
                {
                    return parms.get(i - 1);
                }
            }
        }

        return session.get(name);
    }

    public Object put(String key, Object value)
    {
        return session.put(key, value);
    }

    @Override
    public String toString()
    {
        return source.toString().trim().replaceAll("\n+", "\n").replaceAll(
            "([^\\\\{}(\\[])[\\s\n]*\n", "$1;").replaceAll("[ \\\\\t\n]+", " ");
    }

    /**
     * List that overrides toString() for implicit $args expansion.
     * Also checks for index out of bounds, so that $1 evaluates to null
     * rather than throwing IndexOutOfBoundsException.
     * e.g. x = { a$args }; x 1 2 => a1 2 and not a[1, 2]
     */
    class ArgList extends AbstractList<Object>
    {
        private List<Object> list;

        public ArgList(List<Object> args)
        {
            this.list = args;
        }

        @Override
        public String toString()
        {
            StringBuilder buf = new StringBuilder();
            for (Object o : list)
            {
                if (buf.length() > 0)
                    buf.append(' ');
                buf.append(o);
            }
            return buf.toString();
        }

        @Override
        public Object get(int index)
        {
            return index < list.size() ? list.get(index) : null;
        }

        @Override
        public Object remove(int index)
        {
            return list.remove(index);
        }

        @Override
        public int size()
        {
            return list.size();
        }
    }

}