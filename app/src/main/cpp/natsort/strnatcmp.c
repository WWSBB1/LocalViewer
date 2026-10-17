/* -*- mode: c; c-file-style: "k&r" -*-

  strnatcmp.c -- Perform 'natural order' comparisons of strings in C.
  Copyright (C) 2000, 2004 by Martin Pool <mbp sourcefrog net>

  This software is provided 'as-is', without any express or implied
  warranty.  In no event will the authors be held liable for any damages
  arising from the use of this software.

  Permission is granted to anyone to use this software for any purpose,
  including commercial applications, and to alter it and redistribute it
  freely, subject to the following restrictions:

  1. The origin of this software must not be misrepresented; you must not
     claim that you wrote the original software. If you use this software
     in a product, an acknowledgment in the product documentation would be
     appreciated but is not required.
  2. Altered source versions must be plainly marked as such, and must not be
     misrepresented as being the original software.
  3. This notice may not be removed or altered from any source distribution.
*/


/* partial change history:
 *
 * 2004-10-10 mbp: Lift out character type dependencies into macros.
 *
 * Eric Sosman pointed out that ctype functions take a parameter whose
 * value must be that of an unsigned int, even on platforms that have
 * negative chars in their default char type.
 */

#include <ctype.h>
#include <stddef.h>

#include "strnatcmp.h"


/* These are defined as macros to make it easier to adapt this code to
 * different characters types or comparison functions. */
static inline int
nat_isdigit(const char *a)
{
     return (unsigned char) *a >= '0' && (unsigned char) *a <= '9';
}


/**
 * Case-insensitive primary comparison, case-sensitive tie-break.
 *
 * Must match Kotlin `naturalCompare` (MediaTypes.kt): the browse grid sorts names
 * with it while the reader's page order comes from this C sort. Any difference shows
 * up as a cover that is not page 0 / a gallery whose pages are in another order.
 * ASCII-only on purpose — both sides lower ASCII only.
 */
static inline int
nat_fold(int c)
{
     return (c >= 'A' && c <= 'Z') ? (c - 'A' + 'a') : c;
}


/**
 * Compare one digit run at *pa / *pb, mirroring Kotlin `naturalCompare`:
 *   1. significant digits (leading zeros skipped) — fewer digits = smaller number
 *   2. digit-by-digit for equal magnitude
 *   3. digit-run length, so `1.jpg` sorts before `01.jpg` (Kotlin's tie-break)
 * Advances both pointers past their runs. Returns 0 when the runs are identical.
 */
static int
compare_right(const char **pa, const char **pb)
{
     const char *a = *pa;
     const char *b = *pb;
     const char *aRun = a;
     const char *bRun = b;
     const char *aSig;
     const char *bSig;
     size_t lenA, lenB;

     while (nat_isdigit(a))
          a++;
     while (nat_isdigit(b))
          b++;

     aSig = aRun;
     bSig = bRun;
     /* Keep at least one digit: an all-zero run compares as a single zero. */
     while (aSig < a - 1 && *aSig == '0')
          aSig++;
     while (bSig < b - 1 && *bSig == '0')
          bSig++;

     lenA = (size_t) (a - aSig);
     lenB = (size_t) (b - bSig);
     *pa = a;
     *pb = b;

     if (lenA != lenB)
          return lenA < lenB ? -1 : +1;
     for (; lenA > 0; lenA--, aSig++, bSig++) {
          if (*aSig != *bSig)
               return (unsigned char) *aSig < (unsigned char) *bSig ? -1 : +1;
     }
     /* Same numeric value: shorter spelling first ("1" before "01"). */
     lenA = (size_t) (a - aRun);
     lenB = (size_t) (b - bRun);
     if (lenA != lenB)
          return lenA < lenB ? -1 : +1;
     return 0;
}


int
strnatcmp(const char *a, const char *b)
{
    int result;

    while (1) {
         /* No space skipping: Kotlin `naturalCompare` compares spaces as ordinary
          * characters, and the reader page order must match the browse listing. */
          /* process run of digits */
          if (nat_isdigit(a)  &&  nat_isdigit(b)) {
               if ((result = compare_right(&a, &b)) != 0)
                    return result;
               continue;
          }

          if (!*a && !*b)
               return 0;

          /* Case-insensitive primary, case-sensitive tie-break — same contract as the
             Kotlin comparator, so `A.jpg` / `a.jpg` cannot order differently in the
             grid and in the reader. */
          {
               int ca = (unsigned char) *a;
               int cb = (unsigned char) *b;
               int fa = nat_fold(ca);
               int fb = nat_fold(cb);
               if (fa < fb)
                    return -1;
               if (fa > fb)
                    return +1;
               if (ca < cb)
                    return -1;
               if (ca > cb)
                    return +1;
          }

          a++; b++;
     }
}

