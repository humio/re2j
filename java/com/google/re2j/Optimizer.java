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
      }

      if (isNopAt(prog.start, prog)) { // Eliminate NOP as first instruction.
	prog.start = prog.inst[prog.start].out; // Skip the NOP.
	changes++;
      }
      System.out.println("DBG| Optimizer round #"+round+": "+changes+" changes");
    } while (changes > 0);
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
	int rune1 = a.runes[0];
	if (b.op == Inst.RUNE1) {
	  int rune2 = prog.inst[inst.arg].runes[0];
	  if (rune1 != rune2) {
	    // Rewrite ALT(RUNE1(X), RUNE1(Y)) to ALT_RUNE1(X, RUNE1(Y)):
	    inst.op = Inst.ALT_RUNE1;
	    inst.runes = a.runes;
	    inst.out = a.out;
	    return true;
	  }
	}
    }
    return false;
  }

}
