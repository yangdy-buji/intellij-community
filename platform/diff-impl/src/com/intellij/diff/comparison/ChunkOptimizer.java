/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.diff.comparison;

import com.intellij.diff.comparison.ByLine.Line;
import com.intellij.diff.comparison.ByWord.InlineChunk;
import com.intellij.diff.comparison.ByWord.NewlineChunk;
import com.intellij.diff.comparison.iterables.FairDiffIterable;
import com.intellij.diff.util.Range;
import com.intellij.diff.util.Side;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import static com.intellij.diff.comparison.TrimUtil.expandBackward;
import static com.intellij.diff.comparison.TrimUtil.expandForward;
import static com.intellij.diff.comparison.iterables.DiffIterableUtil.createUnchanged;
import static com.intellij.diff.comparison.iterables.DiffIterableUtil.fair;
import static com.intellij.openapi.util.text.StringUtil.isWhiteSpace;

abstract class ChunkOptimizer<T> {
  @NotNull protected final List<T> myData1;
  @NotNull protected final List<T> myData2;
  @NotNull private final FairDiffIterable myIterable;

  @NotNull protected final ProgressIndicator myIndicator;

  public ChunkOptimizer(@NotNull List<T> data1,
                        @NotNull List<T> data2,
                        @NotNull FairDiffIterable iterable,
                        @NotNull ProgressIndicator indicator) {
    myData1 = data1;
    myData2 = data2;
    myIterable = iterable;
    myIndicator = indicator;
  }

  @NotNull
  public FairDiffIterable build() {
    List<Range> newRanges = new ArrayList<Range>();

    for (Range range2 : myIterable.iterateUnchanged()) {
      Range range1 = ContainerUtil.getLastItem(newRanges);
      if (range1 == null ||
          (range1.end1 != range2.start1 && range1.end2 != range2.start2)) {
        // if changes do not touch and we still can perform one of these optimisations,
        // it means that given DiffIterable is not LCS (because we can build a smaller one). This should not happen.
        newRanges.add(range2);
        continue;
      }

      int count1 = range1.end1 - range1.start1;
      int count2 = range2.end1 - range2.start1;

      int equalForward = expandForward(myData1, myData2, range1.end1, range1.end2, range1.end1 + count2, range1.end2 + count2);
      int equalBackward = expandBackward(myData1, myData2, range2.start1 - count1, range2.start2 - count1, range2.start1, range2.start2);

      // merge chunks left [A]B[B] -> [AB]B
      if (equalForward == count2) {
        newRanges.remove(newRanges.size() - 1);
        newRanges.add(new Range(range1.start1, range1.end1 + count2, range1.start2, range1.end2 + count2));
        continue;
      }

      // merge chunks right [A]A[B] -> A[AB]
      if (equalBackward == count1) {
        newRanges.remove(newRanges.size() - 1);
        newRanges.add(new Range(range2.start1 - count1, range2.end1, range2.start2 - count1, range2.end2));
        continue;
      }


      Side touchSide = Side.fromLeft(range1.end1 == range2.start1);

      int shift = getShift(touchSide, equalForward, equalBackward, range1, range2);
      if (shift == 0) {
        newRanges.add(range2);
      }
      else {
        newRanges.remove(newRanges.size() - 1);
        newRanges.add(new Range(range1.start1, range1.end1 + shift, range1.start2, range1.end2 + shift));
        newRanges.add(new Range(range2.start1 + shift, range2.end1, range2.start2 + shift, range2.end2));
      }
    }

    return fair(createUnchanged(newRanges, myData1.size(), myData2.size()));
  }

  // 0 - do nothing
  // >0 - shift forward
  // <0 - shift backward
  protected abstract int getShift(@NotNull Side touchSide, int equalForward, int equalBackward,
                                  @NotNull Range range1, @NotNull Range range2);

  //
  // Implementations
  //

  /*
   * 1. Minimise amount of chunks
   *      good: "AX[AB]" - "[AB]"
   *      bad: "[A]XA[B]" - "[A][B]"
   *
   * 2. Minimise amount of modified 'sentences', where sentence is a sequence of words, that are not separated by whitespace
   *      good: "[AX] [AZ]" - "[AX] AY [AZ]"
   *      bad: "[AX A][Z]" - "[AX A]Y A[Z]"
   *      ex: "1.0.123 1.0.155" vs "1.0.123 1.0.134 1.0.155"
   */
  public static class WordChunkOptimizer extends ChunkOptimizer<InlineChunk> {
    @NotNull private final CharSequence myText1;
    @NotNull private final CharSequence myText2;

    public WordChunkOptimizer(@NotNull List<InlineChunk> words1,
                              @NotNull List<InlineChunk> words2,
                              @NotNull CharSequence text1,
                              @NotNull CharSequence text2,
                              @NotNull FairDiffIterable changes,
                              @NotNull ProgressIndicator indicator) {
      super(words1, words2, changes, indicator);
      myText1 = text1;
      myText2 = text2;
    }

    @Override
    protected int getShift(@NotNull Side touchSide, int equalForward, int equalBackward, @NotNull Range range1, @NotNull Range range2) {
      List<InlineChunk> touchWords = touchSide.select(myData1, myData2);
      CharSequence touchText = touchSide.select(myText1, myText2);
      int touchStart = touchSide.select(range2.start1, range2.start2);

      // check if chunks are already separated by whitespaces
      if (isSeparatedWithWhitespace(touchText, touchWords.get(touchStart - 1), touchWords.get(touchStart))) return 0;

      // shift chunks left [X]A Y[A ZA] -> [XA] YA [ZA]
      //                   [X][A ZA] -> [XA] [ZA]
      int leftShift = findSequenceEdgeShift(touchText, touchWords, touchStart, equalForward, true);
      if (leftShift > 0) return leftShift;

      // shift chunks right [AX A]Y A[Z] -> [AX] AY [AZ]
      //                    [AX A][Z] -> [AX] [AZ]
      int rightShift = findSequenceEdgeShift(touchText, touchWords, touchStart - 1, equalBackward, false);
      if (rightShift > 0) return -rightShift;

      // nothing to do
      return 0;
    }

    private static int findSequenceEdgeShift(@NotNull CharSequence text, @NotNull List<InlineChunk> words, int offset, int count,
                                             boolean leftToRight) {
      for (int i = 0; i < count; i++) {
        InlineChunk word1;
        InlineChunk word2;
        if (leftToRight) {
          word1 = words.get(offset + i);
          word2 = words.get(offset + i + 1);
        }
        else {
          word1 = words.get(offset - i - 1);
          word2 = words.get(offset - i);
        }
        if (isSeparatedWithWhitespace(text, word1, word2)) return i + 1;
      }
      return -1;
    }

    private static boolean isSeparatedWithWhitespace(@NotNull CharSequence text, @NotNull InlineChunk word1, @NotNull InlineChunk word2) {
      if (word1 instanceof NewlineChunk || word2 instanceof NewlineChunk) return true;

      int offset1 = word1.getOffset2();
      int offset2 = word2.getOffset1();

      for (int i = offset1; i < offset2; i++) {
        if (isWhiteSpace(text.charAt(i))) return true;
      }
      return false;
    }
  }

  /*
   * 1. Minimise amount of chunks
   *      good: "AX[AB]" - "[AB]"
   *      bad: "[A]XA[B]" - "[A][B]"
   *
   * 2. Prefer insertions/deletions, that are bounded by empty(or 'unimportant') line
   *      good: "ABooYZ [ABuuYZ ]ABzzYZ" - "ABooYZ []ABzzYZ"
   *      bad: "ABooYZ AB[uuYZ AB]zzYZ" - "ABooYZ AB[]zzYZ"
   */
  public static class LineChunkOptimizer extends ChunkOptimizer<Line> {
    private final int myThreshold;

    public LineChunkOptimizer(@NotNull List<Line> lines1,
                              @NotNull List<Line> lines2,
                              @NotNull FairDiffIterable changes,
                              @NotNull ProgressIndicator indicator) {
      super(lines1, lines2, changes, indicator);
      myThreshold = Registry.intValue("diff.unimportant.line.char.count");
    }

    @Override
    protected int getShift(@NotNull Side touchSide, int equalForward, int equalBackward, @NotNull Range range1, @NotNull Range range2) {
      List<Line> touchLines = touchSide.select(myData1, myData2);
      int touchStart = touchSide.select(range2.start1, range2.start2);

      int shiftForward = findUnimportantLineShift(touchLines, touchStart, equalForward, true, 0);
      int shiftBackward = findUnimportantLineShift(touchLines, touchStart - 1, equalBackward, false, 0);

      if (shiftForward == -1 && shiftBackward == -1 && myThreshold != 0) {
        shiftForward = findUnimportantLineShift(touchLines, touchStart, equalForward, true, myThreshold);
        shiftBackward = findUnimportantLineShift(touchLines, touchStart - 1, equalBackward, false, myThreshold);
      }

      if (shiftForward == 0 || shiftBackward == 0) return 0;
      if (shiftForward == -1 && shiftBackward == -1) return 0;

      return shiftForward != -1 ? shiftForward : -shiftBackward;
    }

    private static int findUnimportantLineShift(@NotNull List<Line> lines, int offset, int count, boolean leftToRight, int threshold) {
      for (int i = 0; i < count; i++) {
        int index = leftToRight ? offset + i : offset - i;
        if (lines.get(index).getNonSpaceChars() <= threshold) {
          return i;
        }
      }
      return -1;
    }
  }
}
