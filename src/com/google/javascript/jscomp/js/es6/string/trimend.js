/*
 * Copyright 2019 The Closure Compiler Authors.
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

/**
 * @fileoverview
 * @suppress {uselessCode}
 */
'require util/polyfill';

// Most browsers implemented trimRight around ES5-time-frame, but it wasn't
// officially part of the language specification until ES_2019, so we have
// to provide a polyfill for it.
// IE11 doesn't have it, of course...
$jscomp.polyfill('String.prototype.trimRight', function(orig) {
  /**
   * @this {string}
   * @return {string}
   */
  function polyfill() {
    return this.replace(/[\s\xa0]+$/, '');
  }
  return orig || polyfill;
}, 'es_2019', 'es3');

$jscomp.polyfill('String.prototype.trimEnd', function(orig) {
  return orig || String.prototype.trimRight;
}, 'es_2019', 'es3');
