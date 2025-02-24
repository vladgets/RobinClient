/* NameCallback.java -- callback for user names.
   Copyright (C) 2003, Free Software Foundation, Inc.

This file is part of GNU Classpath.

GNU Classpath is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2, or (at your option)
any later version.

GNU Classpath is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with GNU Classpath; see the file COPYING.  If not, write to the
Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
02110-1301 USA.

Linking this library statically or dynamically with other modules is
making a combined work based on this library.  Thus, the terms and
conditions of the GNU General Public License cover the whole
combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent
modules, and to copy and distribute the resulting executable under
terms of your choice, provided that you also meet, for each linked
independent module, the terms and conditions of the license of that
module.  An independent module is a module which is not derived from
or based on this library.  If you modify this library, you may extend
this exception to your version of the library, but you are not
obligated to do so.  If you do not wish to do so, delete this
exception statement from your version. */


package compat.javax.security.auth.callback;

import java.io.Serializable;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;

/**
 * Underlying security services instantiate and pass a <code>NameCallback</code>
 * to the <code>handle()</code> method of a {@link CallbackHandler} to retrieve
 * name information.
 *
 * @see CallbackHandler
 */
public class NameCallback implements Callback, Serializable
{

  // Constants and variables
  // -------------------------------------------------------------------------

  /**
   * @serial
   * @since 1.4
   */
  private String prompt;

  /**
   * @serial
   * @since 1.4
   */
  private String defaultName;

  /**
   * @serial
   * @since 1.4
   */
  private String inputName;

  // Constructor(s)
  // -------------------------------------------------------------------------

  /**
   * Construct a <code>NameCallback</code> with a prompt.
   *
   * @param prompt the prompt used to request the name.
   * @throws IllegalArgumentException if <code>prompt</code> is <code>null</code>
   * or if <code>prompt</code> has a length of <code>0</code>.
   */
  public NameCallback(String prompt)
  {
    super();

    setPrompt(prompt);
  }

  /**
   * Construct a <code>NameCallback</code> with a prompt and default name.
   *
   * @param prompt the prompt used to request the information.
   * @param defaultName the name to be used as the default name displayed with
   * the prompt.
   * @throws IllegalArgumentException if <code>prompt</code> is <code>null</code>
   * or if <code>prompt</code> has a length of <code>0</code>, if
   * <code>defaultName</code> is <code>null</code>, or if <code>defaultName</code>
   * has a length of <code>0</code>.
   */
  public NameCallback(String prompt, String defaultName)
    throws IllegalArgumentException
  {
    super();

    setPrompt(prompt);
    setDefaultName(defaultName);
  }

  // Class methods
  // -------------------------------------------------------------------------

  // Instance methods
  // -------------------------------------------------------------------------

  /**
   * Get the prompt.
   *
   * @return the prompt.
   */
  public String getPrompt()
  {
    return prompt;
  }

  /**
   * Get the default name.
   *
   * @return the default name, or <code>null</code> if this
   * <code>NameCallback</code> was not instantiated with a
   * <code>defaultName</code>.
   */
  public String getDefaultName()
  {
    return defaultName;
  }

  /**
   * Set the retrieved name.
   *
   * @param name the retrieved name (which may be <code>null</code>).
   * @see #getName()
   */
  public void setName(String name)
  {
    this.inputName = name;
  }

  /**
   * Get the retrieved name.
   *
   * @return the retrieved name (which may be <code>null</code>)
   * @see #setName(String)
   */
  public String getName()
  {
    return inputName;
  }

  private void setPrompt(String prompt) throws IllegalArgumentException
  {
    if ((prompt == null) || (prompt.length() == 0))
      {
	throw new IllegalArgumentException("invalid prompt");
      }
    this.prompt = prompt;
  }

  private void setDefaultName(String defaultName) throws IllegalArgumentException
  {
    if ((defaultName == null) || (defaultName.length() == 0))
      {
	throw new IllegalArgumentException("invalid default name");
      }
    this.defaultName = defaultName;
  }
}
