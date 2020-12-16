/*
 * Copyright (c) 2020 The Go Authors. All rights reserved.
 *
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */

package com.google.re2j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/** Compute the "set of nodes to add" for a given start node.
 * This skips consideration of most of the alt nodes.
 */
class NextSetCompiler {

  public static void computeNextSets(Prog prog) {
    final int len = prog.numInst();

    int[] inDegNonAlts = new int[len];
    int[] inDegAlts = new int[len];
    computeInDegrees(prog, inDegNonAlts, inDegAlts);

    boolean[] inEmptyLoop = computeEmptyLoops(prog);
    if (RE2Flags.verboseCompiler) System.err.println("DBG inEmptyLoop: inEmptyLoop="+Arrays.toString(inEmptyLoop));

    // Compute "sets of nodes to add":
    int[][] addLists = new int[len][];
    for (int pc=0; pc<len; pc++) {
      Inst i = prog.inst[pc];
      if (inDegNonAlts[pc] == 0) continue; // No need to fill this in.
      if (inDegAlts[pc] > 0) continue; // Potentially dangerous to fill in (loop case especially).
      if (i.op != Inst.ALT && i.op != Inst.ALT_MATCH) continue; // The set is a singleton.
      addLists[pc] = computeAddList(pc, prog, inDegNonAlts, inDegAlts, inEmptyLoop);
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


  /** Compute the set of nodes which are part of a no-progress loop. */
  private static boolean[] computeEmptyLoops(final Prog prog) {
    final int len = prog.numInst();
    final boolean[] inEmptyLoop = new boolean[len];

    new Runnable() { // Local state scope.
      final boolean[] onStack = new boolean[len];
      /** pushSeqNum:
       * - -1 means not visited yet.
       * - value &gt;= 0 means "being visited; this is my pushSeqNo".
       */
      final int[] pushSeqNum = new int[len];
      int lastPushSeqNum = 0;

      public void run() {
	Arrays.fill(pushSeqNum, -1);
	for (int pc=0; pc<len; pc++) visit(pc);
	if (RE2Flags.verboseCompiler) System.err.println("DBG computeEmptyLoops: pushSeqNum="+Arrays.toString(pushSeqNum));
      }

      /** Returns pc with lowest pushSeqNum in loop, or -1 if no loop was found. */
      private int visit (int pc) {
	if (RE2Flags.verboseCompiler) System.err.println("DBG computeEmptyLoops: visit("+pc+")");
	if (onStack[pc]) {
	  // A loop!
	  //inEmptyLoop[pc] = true;
	  if (RE2Flags.verboseCompiler) System.err.println("DBG - loops to pc="+pc+" psn="+pushSeqNum[pc]);
	  return pc;
	}
	if (pushSeqNum[pc] >= 0) {
	  // Already visited, but not on stack.
	  if (RE2Flags.verboseCompiler) System.err.println("DBG - already visited");
	  return -1;
	}
	int seqNum = ++lastPushSeqNum;
	pushSeqNum[pc] = seqNum;
	onStack[pc] = true;

	Inst i = prog.inst[pc];
	int bestNode = -1;
	int bestSeqNum = Integer.MAX_VALUE;
	switch (i.op) {
	case Inst.ALT:
	case Inst.ALT_MATCH:
	  // Two successors.
	  {
	    int r = visit(i.out);
	    if (r >= 0 && onStack[r] && pushSeqNum[r] < bestSeqNum) {bestNode=r; bestSeqNum = pushSeqNum[r];}
	  }
	  {
	    int r = visit(i.arg);
	    if (r >= 0 && onStack[r] && pushSeqNum[r] < bestSeqNum) {bestNode=r; bestSeqNum = pushSeqNum[r];}
	  }
	  break;

	case Inst.NOP:
	case Inst.CAPTURE:
	case Inst.EMPTY_WIDTH:
	  // One successor, no progress.
	  {
	    int r = visit(i.out);
	    if (r >= 0 && onStack[r] && pushSeqNum[r] < bestSeqNum) {bestNode=r; bestSeqNum = pushSeqNum[r];}
	  }
	  break;

	case Inst.RUNE:
	case Inst.RUNE1:
	case Inst.RUNE_ANY:
	case Inst.RUNE_ANY_NOT_NL:
	  // Progress - don't recurse.
	  break;

	case Inst.FAIL:
	case Inst.MATCH:
	  // No successors.
	  break;
	}
	onStack[pc] = false;

	if (bestNode >= 0) {
	  inEmptyLoop[pc] = true;
	  if (RE2Flags.verboseCompiler) System.err.println("In an empty loop: @"+pc+" "+i);
	}

	return bestNode;
      }
    }.run();

    return inEmptyLoop;
  }

  private static int[] computeAddList(int pc, Prog prog, int[] inDegNonAlts, int[] inDegAlts, boolean[] inEmptyLoop) {
    ArrayList<Integer> acc = new ArrayList<Integer>();
    computeAddList(pc, prog, inDegNonAlts, inDegAlts, inEmptyLoop, acc, new HashSet<Integer>(), true);
    // Alas, this is what we want, but can't: return acc.toArray(new int[acc.size()]);
    int[] result = new int[acc.size()];
    for (int i=0; i<acc.size(); i++) {
      result[i] = acc.get(i);
    }
    return result;
  }

  private static void computeAddList(int pc, Prog prog, int[] inDegNonAlts, int[] inDegAlts, boolean[] inEmptyLoop, ArrayList<Integer> acc, HashSet<Integer> visited, boolean isRoot) {
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
      if (!inEmptyLoop[pc] && (isRoot || inDegree<2)) {
	// Recurse:
	computeAddList(inst.out, prog, inDegNonAlts, inDegAlts, inEmptyLoop, acc, visited, false);
	computeAddList(inst.arg, prog, inDegNonAlts, inDegAlts, inEmptyLoop, acc, visited, false);
      } else {
	// Postpone to runtime:
	acc.add(pc);
      }
      break;

    case Inst.NOP:
      computeAddList(inst.out, prog, inDegNonAlts, inDegAlts, inEmptyLoop, acc, visited, false);
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
