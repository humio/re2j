/*
 * Copyright (c) 2020 The Go Authors. All rights reserved.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */
// Original Go source here:
// http://code.google.com/p/go/source/browse/src/pkg/regexp/syntax/compile.go

package com.google.re2j;

import java.util.HashSet;

/**
 * Compiler from {@code Regexp} (RE2 abstract syntax) to {@code RE2} (compiled regular expression).
 *
 * The only entry point is {@link #compileRegexp}.
 */
class Compiler {

  /**
   * A fragment of a compiled regular expression program.
   *
   * @see http://swtch.com/~rsc/regexp/regexp1.html
   */
  private static class Frag {
    final int i; // an instruction address (pc).
    int out; // a patch list; see explanation in Prog.java

    Frag() {
      this(0, 0);
    }

    Frag(int i) {
      this(i, 0);
    }

    Frag(int i, int out) {
      this.i = i;
      this.out = out;
    }
  }

  private final Prog prog = new Prog(); // Program being built

  private Compiler() {
    newInst(Inst.FAIL); // always the first instruction
  }

  static Prog compileRegexp(Regexp re) {
    Compiler c = new Compiler();
    Frag f = c.compile(re);
    c.prog.patch(f.out, c.newInst(Inst.MATCH).i);
    c.prog.start = f.i;
    if (RE2.verboseOptimizer) System.err.println("ERK: Prog before optimization:\n  "+c.prog);
    Optimizer.optimize(c.prog);
    if (RE2.verboseOptimizer || RE2.verboseCompiler) System.err.println("ERK: Prog after optimization:\n  "+c.prog);
    assignThreadIDsToInstructions2(c.prog);
    return c.prog;
  }

  private Frag newInst(int op) {
    // TODO(rsc): impose length limit.
    prog.addInst(op);
    return new Frag(prog.numInst() - 1);
  }

  // Returns a no-op fragment.  Sometimes unavoidable.
  private Frag nop() {
    Frag f = newInst(Inst.NOP);
    f.out = f.i << 1;
    return f;
  }

  private Frag fail() {
    return new Frag();
  }

  // Given fragment a, returns (a) capturing as \n.
  // Given a fragment a, returns a fragment with capturing parens around a.
  private Frag cap(int arg) {
    Frag f = newInst(Inst.CAPTURE);
    f.out = f.i << 1;
    prog.getInst(f.i).arg = arg;
    if (prog.numCap < arg + 1) {
      prog.numCap = arg + 1;
    }
    return f;
  }

  // Given fragments a and b, returns ab; a|b
  private Frag cat(Frag f1, Frag f2) {
    // concat of failure is failure
    if (f1.i == 0 || f2.i == 0) {
      return fail();
    }
    // TODO(rsc): elide nop
    prog.patch(f1.out, f2.i);
    return new Frag(f1.i, f2.out);
  }

  // Given fragments for a and b, returns fragment for a|b.
  private Frag alt(Frag f1, Frag f2) {
    // alt of failure is other
    if (f1.i == 0) {
      return f2;
    }
    if (f2.i == 0) {
      return f1;
    }
    Frag f = newInst(Inst.ALT);
    Inst i = prog.getInst(f.i);
    i.out = f1.i;
    i.arg = f2.i;
    f.out = prog.append(f1.out, f2.out);
    return f;
  }

  // Given a fragment for a, returns a fragment for a? or a?? (if nongreedy)
  private Frag quest(Frag f1, boolean nongreedy) {
    Frag f = newInst(Inst.ALT);
    Inst i = prog.getInst(f.i);
    if (nongreedy) {
      i.arg = f1.i;
      f.out = f.i << 1;
    } else {
      i.out = f1.i;
      f.out = f.i << 1 | 1;
    }
    f.out = prog.append(f.out, f1.out);
    return f;
  }

  // Given a fragment a, returns a fragment for a* or a*? (if nongreedy)
  private Frag star(Frag f1, boolean nongreedy) {
    Frag f = newInst(Inst.ALT);
    Inst i = prog.getInst(f.i);
    if (nongreedy) {
      i.arg = f1.i;
      f.out = f.i << 1;
    } else {
      i.out = f1.i;
      f.out = f.i << 1 | 1;
    }
    prog.patch(f1.out, f.i);
    return f;
  }

  // Given a fragment for a, returns a fragment for a+ or a+? (if nongreedy)
  private Frag plus(Frag f1, boolean nongreedy) {
    return new Frag(f1.i, star(f1, nongreedy).out);
  }

  // op is a bitmask of EMPTY_* flags.
  private Frag empty(int op) {
    Frag f = newInst(Inst.EMPTY_WIDTH);
    prog.getInst(f.i).arg = op;
    f.out = f.i << 1;
    return f;
  }

  private Frag rune(int rune, int flags) {
    return rune(new int[] {rune}, flags);
  }

  // flags : parser flags
  private Frag rune(int[] runes, int flags) {
    Frag f = newInst(Inst.RUNE);
    Inst i = prog.getInst(f.i);
    i.runes = runes;
    flags &= RE2.FOLD_CASE; // only relevant flag is FoldCase
    if (runes.length != 1 || Unicode.simpleFold(runes[0]) == runes[0]) {
      flags &= ~RE2.FOLD_CASE; // and sometimes not even that
    }
    i.arg = flags;
    f.out = f.i << 1;
    // Special cases for exec machine.
    if (((flags & RE2.FOLD_CASE) == 0 && runes.length == 1)
        || (runes.length == 2 && runes[0] == runes[1])) {
      i.op = Inst.RUNE1;
      i.theRune = (char)runes[0];
    } else if (runes.length == 2 && runes[0] == 0 && runes[1] == Unicode.MAX_RUNE) {
      i.op = Inst.RUNE_ANY;
    } else if (runes.length == 4
        && runes[0] == 0
        && runes[1] == '\n' - 1
        && runes[2] == '\n' + 1
        && runes[3] == Unicode.MAX_RUNE) {
      i.op = Inst.RUNE_ANY_NOT_NL;
    }
    return f;
  }

  private static final int[] ANY_RUNE_NOT_NL = {0, '\n' - 1, '\n' + 1, Unicode.MAX_RUNE};
  private static final int[] ANY_RUNE = {0, Unicode.MAX_RUNE};

  private Frag compile(Regexp re) {
    switch (re.op) {
      case NO_MATCH:
        return fail();
      case EMPTY_MATCH:
        return nop();
      case LITERAL:
        if (re.runes.length == 0) {
          return nop();
        } else {
          Frag f = null;
          for (int r : re.runes) {
            Frag f1 = rune(r, re.flags);
            f = (f == null) ? f1 : cat(f, f1);
          }
          return f;
        }
      case CHAR_CLASS:
        return rune(re.runes, re.flags);
      case ANY_CHAR_NOT_NL:
        return rune(ANY_RUNE_NOT_NL, 0);
      case ANY_CHAR:
        return rune(ANY_RUNE, 0);
      case BEGIN_LINE:
        return empty(Utils.EMPTY_BEGIN_LINE);
      case END_LINE:
        return empty(Utils.EMPTY_END_LINE);
      case BEGIN_TEXT:
        return empty(Utils.EMPTY_BEGIN_TEXT);
      case END_TEXT:
        return empty(Utils.EMPTY_END_TEXT);
      case WORD_BOUNDARY:
        return empty(Utils.EMPTY_WORD_BOUNDARY);
      case NO_WORD_BOUNDARY:
        return empty(Utils.EMPTY_NO_WORD_BOUNDARY);
      case CAPTURE:
        {
          Frag bra = cap(re.cap << 1), sub = compile(re.subs[0]), ket = cap(re.cap << 1 | 1);
          return cat(cat(bra, sub), ket);
        }
      case STAR:
        return star(compile(re.subs[0]), (re.flags & RE2.NON_GREEDY) != 0);
      case PLUS:
        return plus(compile(re.subs[0]), (re.flags & RE2.NON_GREEDY) != 0);
      case QUEST:
        return quest(compile(re.subs[0]), (re.flags & RE2.NON_GREEDY) != 0);
      case CONCAT:
        if (re.subs.length == 0) {
          return nop();
        } else {
          Frag f = null;
          for (Regexp sub : re.subs) {
            Frag f1 = compile(sub);
            f = (f == null) ? f1 : cat(f, f1);
          }
          return f;
        }
      case ALTERNATE:
        {
          if (re.subs.length == 0) {
            return nop();
          } else {
            Frag f = null;
            for (Regexp sub : re.subs) {
              Frag f1 = compile(sub);
              f = (f == null) ? f1 : alt(f, f1);
            }
            return f;
          }
        }
      default:
        throw new IllegalStateException("regexp: unhandled case in compile");
    }
  }

  /** Simple version - instructions don't share TIDs. */
  private static void assignThreadIDsToInstructions1(Prog prog) {
    int numInst = prog.numInst();
    int nextTid = 0;
    for (int i=0; i < numInst; i++) {
      Inst inst = prog.getInst(i);
      int tid;
      switch (inst.op) {
      case Inst.ALT:
      case Inst.ALT_MATCH:
      case Inst.FAIL:
      case Inst.NOP:
	// These are never scheduled.
	tid = -1;
	break;
      default:
	tid = nextTid++;
      }
      inst.tid = tid;
    }
    prog.maxThreadNum = nextTid;
  }

  /** Let instructions share TIDs in the following case:
      - Instruction A has one precedessor, which is a RUNE1.
      - Instruction B has one precedessor, which is a RUNE1.
      - The runes of the predecessors are distinct.
      - (Or: the predecessor is start-of-program)
 */
  private static void assignThreadIDsToInstructions2(Prog prog) {
    final int numInst = prog.numInst();

    // Step 1: Count the number of predecessors for each instruction.
    final int[] predecessorCount = new int[numInst];
    final int[] aPredecessor = new int[numInst];
    predecessorCount[prog.start]++;
    aPredecessor[prog.start] = -1;

    for (int i=0; i < numInst; i++) {
      Inst inst = prog.getInst(i);
      switch (inst.op) {
      case Inst.ALT:
      case Inst.ALT_MATCH:
	// Two successors.
	predecessorCount[inst.out]++;
	predecessorCount[inst.arg]++;
	aPredecessor[inst.out] = i;
	aPredecessor[inst.arg] = i;
	break;

      case Inst.FAIL:
      case Inst.MATCH:
	// No successors.
	break;

      default:
	// One successor.
	predecessorCount[inst.out]++;
	aPredecessor[inst.out] = i;
	break;
      }
    }

    // Step 2: Assign thread IDs.
    int nextTid = 0;
    int reusableTid = -1;
    HashSet<Integer> runesForLastTid = null;

    //System.out.println("==== Thread-ID assignments:");
    for (int i=0; i < numInst; i++) {
      Inst inst = prog.getInst(i);
      int tid;
      String note = "??";
      switch (inst.op) {
      case Inst.ALT:
      case Inst.ALT_MATCH:
      case Inst.FAIL:
      case Inst.NOP:
	// These are never scheduled.
	tid = -1;
	note = "-";
	break;
      default:
	if (predecessorCount[i] > 1) {
	  tid = nextTid++; // Can't share.
	  note = "multiple predecessors";
	} else {
	  int predPC = aPredecessor[i];
	  if (predPC >= 0 && prog.inst[predPC].op == Inst.RUNE1) {
	    int rune = prog.inst[predPC].runes[0];
	    if (reusableTid >= 0 && !runesForLastTid.contains(rune)) {
	      tid = reusableTid;
	      runesForLastTid.add(rune);
	      note = "reusing (rune="+rune+")";
	    } else {
	      tid = nextTid++; // Can't share with existing; perhaps with future instructions?
	      note = "not reusing (rune="+rune+")";
	      reusableTid = tid;
	      runesForLastTid = new HashSet<Integer>();
	      runesForLastTid.add(rune);
	    }
	  } else {
	    // Not an instruction which can share.
	    tid = nextTid++;
	    note = predPC < 0 ? "start-of-program" : "non-RUNE1 predecessor: pc="+predPC+" op="+prog.inst[predPC].op;
	  }
	}
      }
      inst.tid = tid;
      //System.out.println(i+"\t"+inst+"\t\t// TID="+tid+"  #preds="+predecessorCount[i]+"\tnote: "+note);
    }
    prog.maxThreadNum = nextTid;
  }

}
