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
	if (optAltRune1(pc, inst, prog)) changes++;
	if (optAltRune(pc, inst, prog)) changes++;
	if (optRestructure(pc, inst, prog)) changes++;
      }

      if (isNopAt(prog.start, prog)) { // Eliminate NOP as first instruction.
	prog.start = prog.inst[prog.start].out; // Skip the NOP.
	changes++;
      }
      if (RE2.verboseOptimizer) System.err.println("DBG| Optimizer round #"+round+": "+changes+" changes");
    } while (changes > 0);
    prog.compact();
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
