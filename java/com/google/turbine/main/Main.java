/*
 * Copyright 2016 Google Inc. All Rights Reserved.
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

package com.google.turbine.main;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.turbine.binder.Binder;
import com.google.turbine.binder.Binder.BindingResult;
import com.google.turbine.lower.Lower;
import com.google.turbine.options.TurbineOptions;
import com.google.turbine.options.TurbineOptionsParser;
import com.google.turbine.parse.Parser;
import com.google.turbine.tree.Tree.CompUnit;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/** Main entry point for the turbine CLI. */
public class Main {

  private static final int BUFFER_SIZE = 65536;

  public static void main(String[] args) throws IOException {

    TurbineOptions options = TurbineOptionsParser.parse(Arrays.asList(args));

    ImmutableList<CompUnit> units = parseAll(options);

    BindingResult bound =
        Binder.bind(
            units,
            Iterables.transform(options.classPath(), TO_PATH),
            Iterables.transform(options.bootClassPath(), TO_PATH));

    // TODO(cushon): parallelize
    Map<String, byte[]> lowered = Lower.lowerAll(bound.units(), bound.classPathEnv());

    writeOutput(Paths.get(options.outputFile()), lowered);

    // TODO(cushon): jdeps
    // for now just touch the output file, since Bazel expects it to be created
    if (options.outputDeps().isPresent()) {
      Files.write(Paths.get(options.outputDeps().get()), new byte[0]);
    }
  }

  /** Parse all source files and source jars. */
  // TODO(cushon): parallelize
  private static ImmutableList<CompUnit> parseAll(TurbineOptions options) throws IOException {
    ImmutableList.Builder<CompUnit> units = ImmutableList.builder();
    for (String source : options.sources()) {
      units.add(Parser.parse(new String(Files.readAllBytes(Paths.get(source)), UTF_8)));
    }
    for (String sourceJar : options.sourceJars()) {
      try (JarFile jf = new JarFile(sourceJar)) {
        Enumeration<JarEntry> entries = jf.entries();
        while (entries.hasMoreElements()) {
          JarEntry je = entries.nextElement();
          if (!je.getName().endsWith(".java")) {
            continue;
          }
          if (je.getName().equals("package-info.java")) {
            // TODO(cushon): package-info.java files
            continue;
          }
          units.add(
              Parser.parse(new String(ByteStreams.toByteArray(jf.getInputStream(je)), UTF_8)));
        }
      }
    }
    return units.build();
  }

  /** Write bytecode to the output jar. */
  private static void writeOutput(Path path, Map<String, byte[]> lowered) throws IOException {
    try (OutputStream os = Files.newOutputStream(path);
        BufferedOutputStream bos = new BufferedOutputStream(os, BUFFER_SIZE);
        JarOutputStream jos = new JarOutputStream(bos)) {
      for (Map.Entry<String, byte[]> entry : lowered.entrySet()) {
        JarEntry je = new JarEntry(entry.getKey() + ".class");
        je.setTime(0L); // normalize timestamps to the DOS epoch
        je.setMethod(ZipEntry.STORED);
        byte[] bytes = entry.getValue();
        je.setSize(bytes.length);
        je.setCrc(Hashing.crc32().hashBytes(bytes).padToLong());
        jos.putNextEntry(je);
        jos.write(bytes);
      }
    }
  }

  private static final Function<String, Path> TO_PATH =
      new Function<String, Path>() {
        @Override
        public Path apply(String input) {
          return Paths.get(input);
        }
      };
}