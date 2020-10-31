/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.javascript.jscomp;

import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.parsing.parser.util.format.SimpleFormat;

/**
 * Checks for combinations of options that are incompatible, i.e. will produce incorrect code.
 *
 * <p>This is run by Compiler#compileInternal, which is not run during unit tests. The catch is that
 * it's run after Compiler#initOptions, so if for example you want to change the warningsGuard, you
 * can't do it here.
 *
 * <p>Also, turns off options if the provided options don't make sense together.
 */
public final class CompilerOptionsPreprocessor {

  static void preprocess(CompilerOptions options) {
    if (options.getInlineFunctionsLevel() == CompilerOptions.Reach.NONE
        && options.maxFunctionSizeAfterInlining
            != CompilerOptions.UNLIMITED_FUN_SIZE_AFTER_INLINING) {
      throw new InvalidOptionsException(
          "max_function_size_after_inlining has no effect if inlining is disabled.");
    }

    if (options.dartPass) {
      if (!options.getOutputFeatureSet().contains(FeatureSet.ES5)) {
        throw new InvalidOptionsException("Dart requires --language_out=ES5 or higher.");
      }
      // --dart_pass does not support type-aware property renaming yet.
      options.setAmbiguateProperties(false);
      options.setDisambiguateProperties(false);
    }

    // ECMASCRIPT6_TYPED and TS syntax support exist only for use by Gents
    // (https://github.com/angular/clutz) which outputs TS syntax but does not parse it.
    if (CompilerOptions.LanguageMode.ECMASCRIPT6_TYPED.equals(options.getLanguageIn())) {
      throw new InvalidOptionsException("Cannot set input language to ECMASCRIPT6_TYPED.");
    }
  }

  /** Exception to indicate incompatible options in the CompilerOptions. */
  public static class InvalidOptionsException extends RuntimeException {
    private InvalidOptionsException(String message, Object... args) {
      super(SimpleFormat.format(message, args));
    }
  }

  // Don't instantiate.
  private CompilerOptionsPreprocessor() {
  }
}
