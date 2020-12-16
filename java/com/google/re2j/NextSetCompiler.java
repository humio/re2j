/*
 * Copyright (c) 2020 The Go Authors. All rights reserved.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */

package com.google.re2j;

import java.util.HashSet;
import java.util.ArrayList;

/** Compute the "set of nodes to add" for a given start node.
 * This skips consideration of most of the alt nodes.
 */
class NextSetCompiler {

  public static void computeNextSets(Prog prog) {
    final int len = prog.numInst();

    int[] inDegNonAlts = new int[len];
    int[] inDegAlts = new int[len];
    computeInDegrees(prog, inDegNonAlts, inDegAlts);

    // Compute "sets of nodes to add":
    int[][] addLists = new int[len][];
    for (int pc=0; pc<len; pc++) {
      Inst i = prog.inst[pc];
      if (inDegNonAlts[pc] == 0) continue; // No need to fill this in.
      if (inDegAlts[pc] > 0) continue; // Potentially dangerous to fill in (loop case especially).
      if (i.op != Inst.ALT && i.op != Inst.ALT_MATCH) continue; // The set is a singleton.
      addLists[pc] = computeAddList(pc, prog, inDegNonAlts, inDegAlts);
    }

    prog.addLists = addLists;
  }

  private static void computeInDegrees(Prog prog, int[] fromNonAlts, int[] fromAlts) {
    final int len = prog.numInst();

    fromNonAlts[prog.start]++;

    for (int pc=0; pc<len; pc++) {
      Inst i = prog.inst[pc];
      switch (i.op) {
      case Inst.ALT:
      case Inst.ALT_MATCH:
	// Two successors.
	fromAlts[i.out]++;
	fromAlts[i.arg]++;
	break;

      case Inst.NOP:
      case Inst.CAPTURE:
      case Inst.EMPTY_WIDTH:
      case Inst.RUNE:
      case Inst.RUNE1:
      case Inst.RUNE_ANY:
      case Inst.RUNE_ANY_NOT_NL:
	// One successor.
	fromNonAlts[i.out]++;
	break;

      case Inst.FAIL:
      case Inst.MATCH:
	// No successors.
	break;
      }
    }
  }

  private static int[] computeAddList(int pc, Prog prog, int[] inDegNonAlts, int[] inDegAlts) {
    ArrayList<Integer> acc = new ArrayList<Integer>();
    computeAddList(pc, prog, inDegNonAlts, inDegAlts, acc, new HashSet<Integer>(), true);
    // Alas, this is what we want, but can't: return acc.toArray(new int[acc.size()]); 
    int[] result = new int[acc.size()];
    for (int i=0; i<acc.size(); i++) {
      result[i] = acc.get(i);
    }
    return result;
  }

  private static void computeAddList(int pc, Prog prog, int[] inDegNonAlts, int[] inDegAlts, ArrayList<Integer> acc, HashSet<Integer> visited, boolean isRoot) {
    if (!visited.add(pc)) return; // Already visited.

    Inst inst = prog.inst[pc];
    switch (inst.op) {
    default:
      throw new IllegalStateException("unhandled");

    case Inst.FAIL:
      break; // nothing

    case Inst.ALT:
    case Inst.ALT_MATCH:
      int inDegree = inDegAlts[pc] + inDegNonAlts[pc];
      if (/*isRoot ||*/ inDegree<2) {
	// Recurse:
	computeAddList(inst.out, prog, inDegNonAlts, inDegAlts, acc, visited, false);
	computeAddList(inst.arg, prog, inDegNonAlts, inDegAlts, acc, visited, false);
      } else {
	// Postpone to runtime:
	acc.add(pc);
      }
      break;

    case Inst.NOP:
      computeAddList(inst.out, prog, inDegNonAlts, inDegAlts, acc, visited, false);
      break;

    case Inst.EMPTY_WIDTH:
    case Inst.CAPTURE:
    case Inst.MATCH:
    case Inst.RUNE:
    case Inst.RUNE1:
    case Inst.RUNE_ANY:
    case Inst.RUNE_ANY_NOT_NL:
      acc.add(pc);
      break;
    }
  }
}
