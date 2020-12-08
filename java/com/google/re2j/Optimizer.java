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
      }

      if (isNopAt(prog.start, prog)) { // Eliminate NOP as first instruction.
	prog.start = prog.inst[prog.start].out; // Skip the NOP.
	changes++;
      }
      if (RE2Flags.verboseOptimizer) System.out.println("DBG| Optimizer round #"+round+": "+changes+" changes");
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
      // Two successors.
      if (isNopAt(inst.arg, prog)) {
	inst.arg = succOf(inst.arg, prog);
	changed = true;
      }
      // Fall-though.

    case Inst.CAPTURE:
    case Inst.EMPTY_WIDTH:
    case Inst.RUNE:
    case Inst.RUNE1:
    case Inst.RUNE_ANY:
    case Inst.RUNE_ANY_NOT_NL:
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

}
