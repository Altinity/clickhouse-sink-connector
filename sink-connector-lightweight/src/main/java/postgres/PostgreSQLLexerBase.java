/*
PostgreSQL grammar.
The MIT License (MIT).
Copyright (c) 2021-2023, Oleksii Kovalov (Oleksii.Kovalov@outlook.com).
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

package postgres;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

import java.util.Stack;

/**
 * Base class for the ANTLR-generated PostgreSQLLexer.
 * Provides helper predicates used by lexer actions in PostgreSQLLexer.g4.
 */
public abstract class PostgreSQLLexerBase extends Lexer {

    /** Stack used to track dollar-quoted string tags (e.g. {@code $tag$...$tag$}). */
    protected final Stack<String> tags = new Stack<>();

    protected PostgreSQLLexerBase(CharStream input) {
        super(input);
    }

    /** Push the current lexeme onto the dollar-quote tag stack. */
    public void PushTag() {
        tags.push(getText());
    }

    /** Return true if the current lexeme matches the top of the tag stack. */
    public boolean IsTag() {
        return getText().equals(tags.peek());
    }

    /** Pop the top tag off the dollar-quote tag stack. */
    public void PopTag() {
        tags.pop();
    }

    /** No-op assertion (debug placeholder from original grammar). */
    public void UnterminatedBlockCommentDebugAssert() {
        // Debug.Assert(InputStream.LA(1) == -1 /*EOF*/);
    }

    /** Returns true when the next character is not {@code -} (used in numeric rules). */
    public boolean CheckLaMinus() {
        return getInputStream().LA(1) != '-';
    }

    /** Returns true when the next character is not {@code *}. */
    public boolean CheckLaStar() {
        return getInputStream().LA(1) != '*';
    }

    /** Returns true when the character before the current position is a letter. */
    public boolean CharIsLetter() {
        return Character.isLetter(getInputStream().LA(-1));
    }

    /**
     * Called when a numeric literal that started as a float cannot be completed.
     * Rewinds two characters and forces the token type to {@code Integral}.
     */
    public void HandleNumericFail() {
        getInputStream().seek(getInputStream().index() - 2);
        setType(PostgreSQLLexer.Integral);
    }

    /** Resolves ambiguity between {@code <<}/{@code >>} operator tokens. */
    public void HandleLessLessGreaterGreater() {
        if (getText().equals("<<")) setType(PostgreSQLLexer.LESS_LESS);
        if (getText().equals(">>")) setType(PostgreSQLLexer.GREATER_GREATER);
    }

    /** Returns true when the two preceding bytes form a valid UTF-32 letter codepoint. */
    public boolean CheckIfUtf32Letter() {
        int codePoint = (getInputStream().LA(-2) << 8) + getInputStream().LA(-1);
        char[] c;
        if (codePoint < 0x10000) {
            c = new char[]{(char) codePoint};
        } else {
            codePoint -= 0x10000;
            c = new char[]{
                (char) (codePoint / 0x400 + 0xd800),
                (char) (codePoint % 0x400 + 0xdc00)
            };
        }
        return Character.isLetter(c[0]);
    }

    /** Returns true when the next character in the stream is a semicolon. */
    public boolean IsSemiColon() {
        return ';' == (char) getInputStream().LA(1);
    }
}
