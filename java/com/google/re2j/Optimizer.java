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
	if (optRestructure(pc, inst, prog)) changes++;
	if (optTrailingSingleRuneLoop(pc, inst, prog)) changes++;
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
   *  ALT_RUNE1(a, ALT(X,Y); B) otherwise
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
	} else {
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
	return true;
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
	inst.runes = new int[] {0, '\n'-1, '\n'+1, Character.MAX_VALUE};
	inst.out = pc;
	return true;
      } else if (instA.op == Inst.RUNE_ANY && instA.out == pc && alwaysLeadsToMatch(inst.arg, prog)) {
	inst.op = Inst.ALT_RUNE;
	inst.runes = new int[] {0, Character.MAX_VALUE};
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
