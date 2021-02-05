/*
 * Copyright (c) 2020 The Go Authors. All rights reserved.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */

package com.google.re2j;

class Optimizer {
  /** Peep-hole optimization.
   */
  public static void optimize(Prog prog) {
    int round=0;
    int changes;
    do {
      round++;
      changes = 0;

      int len = prog.numInst();
      for (int pc=0; pc<len; pc++) {
	Inst inst = prog.inst[pc];
	if (optNop(pc, inst, prog)) changes++;
	if (optDelayCapture(pc, inst, prog)) changes++;
	if (optAltRune1(pc, inst, prog)) changes++;
	if (optAltRune(pc, inst, prog)) changes++;
	if (optAltRune1Overlapping(pc, inst, prog)) changes++;
        if (optAltBranchOrder(pc, inst, prog)) changes++;
        if (optAltBranchStructure(pc, inst, prog)) changes++;
        if (optAltARune1ARune1(pc, inst, prog)) changes++;
        if (optAltARune1Rune1(pc, inst, prog)) changes++;
        if (optEmptyWidthRune(pc, inst, prog)) changes++;
	// if (optRestructure(pc, inst, prog)) changes++;
	//if (optTrailingSingleRuneLoop(pc, inst, prog)) changes++;
      }

      if (isNopAt(prog.start, prog)) { // Eliminate NOP as first instruction.
	prog.start = prog.inst[prog.start].out; // Skip the NOP.
	changes++;
      }
      if (RE2.verboseOptimizer) System.err.println("DBG| Optimizer round #"+round+": "+changes+" changes");
    } while (changes > 0);
    prog.compact();

    /*
    {
      int len = prog.numInst();
      for (int pc=0; pc<len; pc++) {
	Inst inst = prog.inst[pc];
	if (inst.op == Inst.ALT) {
	  if (alwaysLeadsToMatch(inst.out, prog)) System.err.println("DBG| Alt-A alwaysLeadsToMatch: "+pc+" "+inst);
	  if (alwaysLeadsToMatch(inst.arg, prog)) System.err.println("DBG| Alt-B alwaysLeadsToMatch: "+pc+" "+inst);
	}
      }
    }
    */
  }

  private static boolean alwaysLeadsToMatch(int pc, Prog prog) {
    while (true) {
	Inst inst = prog.inst[pc];
	switch (inst.op) {
	case Inst.MATCH:
	  return true;
	case Inst.CAPTURE:
	case Inst.NOP:
	  pc = inst.out;
	  continue;
	default:
	  return false;
	}
    }
  }

  private static boolean isNopAt(int pc, Prog prog) {
    return prog.inst[pc].op == Inst.NOP;
  }

  private static int succOf(int pc, Prog prog) {
    return prog.inst[pc].out;
  }

  /** Eliminate NOP instructions. */
  private static boolean optNop(int pc, Inst inst, Prog prog) {
    boolean changed = false;

    switch (inst.op) {
    case Inst.ALT:
    case Inst.ALT_MATCH:
    case Inst.ALT_RUNE1:
    case Inst.ALT_RUNE:
      // Two successors.
      if (isNopAt(inst.arg, prog)) {
	inst.arg = succOf(inst.arg, prog);
	changed = true;
      }
      // Fall-though.

    default:
      // One successor.
      if (isNopAt(inst.out, prog)) {
	inst.out = succOf(inst.out, prog);
	changed = true;
      }
      break;

    case Inst.FAIL:
    case Inst.MATCH:
      // No successors.
      break;

    case Inst.NOP:
      // To ensure that nop-nop loops don't hinder termination, we don't do anything in this case.
      break;
    }

    return changed;
  }

  private static boolean optDelayCapture(int pc, Inst inst, Prog prog) {
    if (inst.op != Inst.CAPTURE) return false;
    int nextLabel = inst.out;
    Inst next = prog.inst[nextLabel];
    switch (next.op) {
    case Inst.RUNE:
    case Inst.RUNE1:
    case Inst.RUNE_ANY:
    case Inst.RUNE_ANY_NOT_NL: {
      // Rewrite CAPTURE(offset)->RUNEx to RUNEx->CAPTURE(offset+1)
      // Because: tests before book-keeping.
      int newLabel = newInst(Inst.CAPTURE, prog);
      Inst newInst = prog.inst[newLabel];
      newInst.op = inst.op;
      newInst.arg = inst.arg;
      newInst.arg2 = inst.arg2 + 1;
      newInst.out = next.out;

      inst.op = next.op;
      inst.out = newLabel;
      inst.arg = next.arg;
      inst.theRune = next.theRune;
      inst.runes = next.runes;
      return true;
    }

    case Inst.EMPTY_WIDTH: {
      // Rewrite CAPTURE(offset)->EMPTY_WIDTH to EMPTY_WIDTH->CAPTURE(offset)
      // Because: tests before book-keeping.
      int newLabel = newInst(Inst.CAPTURE, prog);
      Inst newInst = prog.inst[newLabel];
      newInst.op = inst.op;
      newInst.arg = inst.arg;
      newInst.arg2 = inst.arg2;
      newInst.out = next.out;

      inst.op = next.op;
      inst.out = newLabel;
      inst.arg = next.arg;
      return true;
    }

    default:
      return false;
    }
  }

  /** Replace ALT with ALT_RUNE1 under the right circumstances. */
  private static boolean optAltRune1(int pc, Inst inst, Prog prog) {
    if (inst.op == Inst.ALT &&
	prog.inst[inst.out].op == Inst.RUNE1) {
	Inst a = prog.inst[inst.out];
	Inst b = prog.inst[inst.arg];
	if (canBeSecondBranchOfAltRune1(b, prog, a)) {
	  // Rewrite ALT(RUNE1(R), Y) to ALT_RUNE1(R, Y):
	  inst.op = Inst.ALT_RUNE1;
	  inst.theRune = a.theRune;
	  inst.runes = a.runes;
	  inst.out = a.out;
	  return true;
	}
    }
    return false;
  }

  /** Replace ALT with ALT_RUNE under the right circumstances. */
  private static boolean optAltRune(int pc, Inst inst, Prog prog) {
    if (inst.op == Inst.ALT &&
	prog.inst[inst.out].op == Inst.RUNE) {
	Inst a = prog.inst[inst.out];
	Inst b = prog.inst[inst.arg];
	if (canBeSecondBranchOfAltRune(b, prog, a)) {
	  // Rewrite ALT(RUNE(R), Y) to ALT_RUNE(R, Y):
	  inst.op = Inst.ALT_RUNE;
	  inst.runes = a.runes;
	  inst.out = a.out;
	  return true;
	}
    }
    return false;
  }

  /** Replace ALT(A:RUNE1(a) -> X, B:RUNEx -> Y) with
   *  RUNE1 -> ALT(X,Y) if B=RUNE1(a)
   *  //ALT_RUNE1(a, ALT(X,Y); B) otherwise
   *  ALT_RUNE1(a, X; B) if alwaysLeadsToMatch(X)
   */
  private static boolean optAltRune1Overlapping(int pc, Inst inst, Prog prog) {
    if (inst.op == Inst.ALT &&
	prog.inst[inst.out].op == Inst.RUNE1) {
      int bLabel = inst.arg;
      Inst a = prog.inst[inst.out];
      Inst b = prog.inst[bLabel];
      if (isRuneInstruction(b) && runesOverlap(a, b)) {
	if (b.op == Inst.RUNE1) {
	  int newAltLabel = newInst(Inst.ALT, prog);
	  Inst newAlt = prog.inst[newAltLabel];
	  newAlt.out = a.out;
	  newAlt.arg = b.out;

	  inst.op = Inst.RUNE1;
	  inst.theRune = a.theRune;
	  inst.runes = a.runes;
	  inst.out = newAltLabel;
	  return true;
	} else if (alwaysLeadsToMatch(a.out, prog)) {
	  inst.op = Inst.ALT_RUNE1;
	  inst.theRune = a.theRune;
	  inst.runes = a.runes;
	  inst.out = a.out;
	  inst.arg = bLabel;
	  return true;
	}
/*	} else {
	  int newAltLabel = newInst(Inst.ALT, prog);
	  Inst newAlt = prog.inst[newAltLabel];
	  newAlt.out = a.out;
	  newAlt.arg = b.out;

	  inst.op = Inst.ALT_RUNE1;
	  inst.theRune = a.theRune;
	  inst.runes = a.runes;
	  inst.out = newAltLabel;
	  inst.arg = bLabel;
	}
*/
      }
    }
    return false;
  }

  /** Restructure in order to enable other optimizations:
   *  ALT(ALT_RUNE1(r, X), Y) -> ALT_RUNE1(r, ALT(X, Y))
   */
  private static boolean optRestructure(int pc, Inst inst, Prog prog) {
    if (inst.op == Inst.ALT &&
	prog.inst[inst.out].op == Inst.ALT_RUNE1) {
	int label1 = inst.out;
	int label2 = inst.arg;
	Inst oldAltRune = prog.inst[inst.out];

	int newAltLabel = newInst(Inst.ALT, prog);
	Inst newAlt = prog.inst[newAltLabel];
	newAlt.out = oldAltRune.out;
	newAlt.arg = inst.arg;

	inst.op = oldAltRune.op;
	inst.runes = oldAltRune.runes;
	inst.theRune = oldAltRune.theRune;
	inst.out = oldAltRune.out;
	inst.arg = newAltLabel;
	return true;
    }

    if (inst.op == Inst.ALT &&
	prog.inst[inst.out].op == Inst.ALT_RUNE) {
	int label1 = inst.out;
	int label2 = inst.arg;
	Inst oldAltRune = prog.inst[inst.out];

	int newAltLabel = newInst(Inst.ALT, prog);
	Inst newAlt = prog.inst[newAltLabel];
	newAlt.out = oldAltRune.out;
	newAlt.arg = inst.arg;

	inst.op = oldAltRune.op;
	inst.runes = oldAltRune.runes;
	inst.out = oldAltRune.out;
	inst.arg = newAltLabel;
	return true;
    }
    return false;
  }

  /** Rewrite
   *    ALT(ALT_RUNEx(c1,X,Y), ALT_RUNEx(c2,U,V))
   *  as
   *    ALT_RUNEx(c1, ALT(X,U), ALT(Y,V))
   *  if c1 == c2.
   *  TODO: Handle c1 != c2 case.
   */
  private static boolean optAltARune1ARune1(int pc, Inst inst, Prog prog) {
    if (inst.op != Inst.ALT) return false;
    Inst nextA = prog.inst[inst.out];
    Inst nextB = prog.inst[inst.arg];
    if (nextA.op == Inst.ALT_RUNE1 &&
        nextB.op == Inst.ALT_RUNE1) {
      if (nextA.theRune == nextB.theRune) {

	int newAlt1Label = newInst(Inst.ALT, prog);
	Inst newAlt1 = prog.inst[newAlt1Label];
	newAlt1.out = nextA.out;
	newAlt1.arg = nextB.out;

	int newAlt2Label = newInst(Inst.ALT, prog);
	Inst newAlt2 = prog.inst[newAlt2Label];
	newAlt2.out = nextA.arg;
	newAlt2.arg = nextB.arg;

	inst.op = Inst.ALT_RUNE1;
	inst.runes = nextA.runes;
	inst.theRune = nextA.theRune;
	inst.out = newAlt1Label;
	inst.arg = newAlt2Label;

        return true;
      }
    }

    return false;
  }

  /** Rewrite
   *    ALT(ALT_RUNEx(c1,X,Y), RUNEx(c2,U))
   *  as
   *    ALT_RUNEx(c1, ALT(X,U), Y)
   *  if c1 == c2
   *  and as
   *    ALT_RUNEx(c1, X, ALT(Y, RUNEx(c2,U))
   *  if c1 != c2.
   */
  private static boolean optAltARune1Rune1(int pc, Inst inst, Prog prog) {
    if (inst.op != Inst.ALT) return false;
    Inst nextA = prog.inst[inst.out];
    Inst nextB = prog.inst[inst.arg];
    if (nextA.op == Inst.ALT_RUNE1 &&
        nextB.op == Inst.RUNE1) {
      if (nextA.theRune == nextB.theRune) {
	int newAltLabel = newInst(Inst.ALT, prog);
	Inst newAlt = prog.inst[newAltLabel];
	newAlt.out = nextA.out;
	newAlt.arg = nextB.out;

	inst.op = Inst.ALT_RUNE1;
	inst.runes = nextA.runes;
	inst.theRune = nextA.theRune;
	inst.out = newAltLabel;
	inst.arg = nextA.arg;

        return true;
      } else {
	int newAltLabel = newInst(Inst.ALT, prog);
	Inst newAlt = prog.inst[newAltLabel];
	newAlt.out = nextA.arg;
	newAlt.arg = inst.arg; // -> nextB

	inst.op = Inst.ALT_RUNE1;
	inst.runes = nextA.runes;
	inst.theRune = nextA.theRune;
	inst.out = nextA.out;
	inst.arg = newAltLabel;

        return true;
      }
    }

    return false;
  }

  /** Rewrite
   *    ALT(RUNEx(c1) -&gt; X, RUNEx(c2) -&gt; Y
   *  as
   *    ALT(RUNEx(c2) -&gt; X, RUNEx(c1) -&gt; Y
   *  if c1 and c2 are exclusive, and c2 is before c1 according to a total order.
   */
  private static boolean optAltBranchOrder(int pc, Inst inst, Prog prog) {
    if (inst.op != Inst.ALT) return false;
    Inst nextA = prog.inst[inst.out];
    Inst nextB = prog.inst[inst.arg];
    if (nextA.op == Inst.RUNE1 &&
        nextB.op == Inst.RUNE1) {
      if (nextA.arg > nextB.arg) { // Different and in the wrong order. Reorder:
        int tmp = inst.out;
        inst.out = inst.arg;
        inst.arg = tmp;

        return true;
      }
    }

    return false;
  }

  /** Rewrite
   *    ALT(ALT(X,Y), Z)
   *  as
   *    ALT(X, ALT(Y,Z))
   * For termination reasons, only do this if X is _not_ an ALT; we thus process structures inside-out.
   * This is in order to help other peephole patterns.
   */
  private static boolean optAltBranchStructure(int pc, Inst inst, Prog prog) {
    if (inst.op != Inst.ALT) return false;
    Inst alt2 = prog.inst[inst.out];
    if (alt2.op != Inst.ALT) return false;

    int ipX = alt2.out;
    Inst insX = prog.inst[ipX];
    if (insX.op == Inst.ALT) return false; // Process inside-out to ensure termination.

    int ipY = alt2.arg;
    int ipZ = inst.arg;
    if (ipX == pc || ipY == pc || ipZ == pc) return false; // Involved in empty-loop.

    int newLabel = newInst(Inst.ALT, prog);
    Inst newInst = prog.inst[newLabel];
    newInst.out = ipY;
    newInst.arg = ipZ;
    inst.out = ipX;
    inst.arg = newLabel;

    return true;
  }

  /** Rewrite
   *    EMPTY_WIDTH(cond,delta=0) -> RUNEx(...)
   *  as
   *    RUNEx(...) -> EMPTY_WIDTH(cond,delta=1)
   */
  private static boolean optEmptyWidthRune(int pc, Inst inst, Prog prog) {
    if (inst.op == Inst.EMPTY_WIDTH/* && inst.arg2 == 0*/) {
      Inst instA = prog.inst[inst.out];
      if (instA.op == Inst.RUNE1 || instA.op == Inst.RUNE) { // Not any/anynotnl
	int newLabel = newInst(Inst.EMPTY_WIDTH, prog);
	Inst newInst = prog.inst[newLabel];

	newInst.out = instA.out;
	newInst.arg = inst.arg;
	newInst.arg2 = inst.arg2 + 1;

	inst.op = instA.op;
	inst.runes = instA.runes;
	inst.theRune = instA.theRune;
	inst.out = newLabel;

	return true;
      }
    }
    return false;
  }

  /** Detect trailing single-rune loops:
   * Replace loop:ALT(RUNEx->loop, ...->Match) with
   * loop:ALT_RUNEx(loop, ...->Match).
   */
  private static boolean optTrailingSingleRuneLoop(int pc, Inst inst, Prog prog) {
    if (inst.op == Inst.ALT) {
      Inst instA = prog.inst[inst.out];
      if (instA.op == Inst.RUNE1 && instA.out == pc && alwaysLeadsToMatch(inst.arg, prog)) {
	inst.op = Inst.ALT_RUNE1;
	inst.theRune = instA.theRune;
	inst.runes = instA.runes;
	inst.out = pc;
	return true;
      } else if (instA.op == Inst.RUNE && instA.out == pc && alwaysLeadsToMatch(inst.arg, prog)) {
	inst.op = Inst.ALT_RUNE;
	inst.runes = instA.runes;
	inst.out = pc;
	return true;
      } else if (instA.op == Inst.RUNE_ANY_NOT_NL && instA.out == pc && alwaysLeadsToMatch(inst.arg, prog)) {
	inst.op = Inst.ALT_RUNE;
	inst.runes = new int[] {0, '\n'-1, '\n'+1, 0x10ffff};
	inst.out = pc;
	return true;
      } else if (instA.op == Inst.RUNE_ANY && instA.out == pc && alwaysLeadsToMatch(inst.arg, prog)) {
	inst.op = Inst.ALT_RUNE;
	inst.runes = new int[] {0, 0x10ffff};
	inst.out = pc;
	return true;
      }
    }
    return false;
  }

  private static boolean canBeSecondBranchOfAltRune1(Inst inst, Prog prog, Inst runeInstToNotOverlap) {
    // Only RUNE1 is supported in the first branch at present:
    if (runeInstToNotOverlap.op != Inst.RUNE1) return false;
    int rune = runeInstToNotOverlap.theRune;

    while (true) {
      switch (inst.op) {
      case Inst.ALT_RUNE1:
	if (rune == inst.theRune) return false;
	inst = prog.inst[inst.arg];
	break;

      case Inst.ALT_RUNE:
	if (inst.matchRune(rune)) return false;
	inst = prog.inst[inst.arg];
	break;

      case Inst.RUNE:
	return !inst.matchRune(rune);

      case Inst.RUNE1:
	return rune != inst.theRune;

      case Inst.RUNE_ANY:
	return false;

      case Inst.RUNE_ANY_NOT_NL:
	return rune == '\n';

      // Enable these when we know how to handle them in the Machine:
      case Inst.CAPTURE:
      // case Inst.EMPTY_WIDTH:
      	inst = prog.inst[inst.out];
      	break;

      default:
	return false;
      }
    }
  }

  private static boolean canBeSecondBranchOfAltRune(Inst inst, Prog prog, Inst runeInstToNotOverlap) {
    while (true) {
      switch (inst.op) {
      case Inst.ALT_RUNE1:
	if (runeInstToNotOverlap.matchRune(inst.theRune)) return false;
	inst = prog.inst[inst.arg];
	break;

      case Inst.ALT_RUNE:
	if (runesOverlap(inst, runeInstToNotOverlap)) return false;
	inst = prog.inst[inst.arg];
	break;

      case Inst.RUNE:
	if (runesOverlap(inst, runeInstToNotOverlap)) return false;
	return true;

      case Inst.RUNE1:
	return !runeInstToNotOverlap.matchRune(inst.theRune);

      case Inst.RUNE_ANY:
	return false;

      case Inst.RUNE_ANY_NOT_NL:
	return false;

      // Enable these when we know how to handle them in the Machine:
      case Inst.CAPTURE:
      // case Inst.EMPTY_WIDTH:
      	inst = prog.inst[inst.out];
      	break;

      default:
	return false;
      }
    }
  }

  private static boolean isRuneInstruction(Inst inst) {
    switch (inst.op) {
      case Inst.RUNE:
      case Inst.RUNE1:
      case Inst.RUNE_ANY:
      case Inst.RUNE_ANY_NOT_NL:
	return true;
    default:
      return false;
    }
  }

  private static boolean runesOverlap(Inst a, Inst b)  {
    for (int i=0; i<a.runes.length; i++) if (b.matchRune(a.runes[i])) return true;
    for (int i=0; i<b.runes.length; i++) if (a.matchRune(b.runes[i])) return true;
    return false;
  }

  private static int newInst(int op, Prog prog) {
    prog.addInst(op);
    return prog.numInst() - 1;
  }

}
