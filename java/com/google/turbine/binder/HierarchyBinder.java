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

package com.google.turbine.binder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.BoundClass;
import com.google.turbine.binder.bound.HeaderBoundClass;
import com.google.turbine.binder.bound.PackageSourceBoundClass;
import com.google.turbine.binder.bound.SourceHeaderBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.TyVarSymbol;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.diag.TurbineError.ErrorKind;
import com.google.turbine.model.TurbineFlag;
import com.google.turbine.model.TurbineTyKind;
import com.google.turbine.model.TurbineVisibility;
import com.google.turbine.tree.Tree;
import com.google.turbine.tree.TurbineModifier;
import java.util.ArrayDeque;

/** Type hierarchy binding. */
public class HierarchyBinder {

  /** Binds the type hierarchy (superclasses and interfaces) for a single class. */
  public static SourceHeaderBoundClass bind(
      ClassSymbol origin,
      PackageSourceBoundClass base,
      Env<ClassSymbol, ? extends HeaderBoundClass> env) {
    return new HierarchyBinder(origin, base, env).bind();
  }

  private final ClassSymbol origin;
  private final PackageSourceBoundClass base;
  private final Env<ClassSymbol, ? extends HeaderBoundClass> env;

  private HierarchyBinder(
      ClassSymbol origin,
      PackageSourceBoundClass base,
      Env<ClassSymbol, ? extends HeaderBoundClass> env) {
    this.origin = origin;
    this.base = base;
    this.env = env;
  }

  private SourceHeaderBoundClass bind() {
    Tree.TyDecl decl = base.decl();

    int access = 0;
    for (TurbineModifier m : decl.mods()) {
      access |= m.flag();
    }
    switch (decl.tykind()) {
      case CLASS:
        access |= TurbineFlag.ACC_SUPER;
        break;
      case INTERFACE:
        access |= TurbineFlag.ACC_ABSTRACT | TurbineFlag.ACC_INTERFACE;
        break;
      case ENUM:
        access |= TurbineFlag.ACC_ENUM | TurbineFlag.ACC_SUPER;
        break;
      case ANNOTATION:
        access |= TurbineFlag.ACC_ABSTRACT | TurbineFlag.ACC_INTERFACE | TurbineFlag.ACC_ANNOTATION;
        break;
      default:
        throw new AssertionError(decl.tykind());
    }

    // types declared in interfaces  annotations are implicitly public (JLS 9.5)
    if (enclosedByInterface(base)) {
      access = TurbineVisibility.PUBLIC.setAccess(access);
    }

    if ((access & TurbineFlag.ACC_STATIC) == 0 && implicitStatic(base)) {
      access |= TurbineFlag.ACC_STATIC;
    }

    if (decl.tykind() == TurbineTyKind.INTERFACE) {
      access |= TurbineFlag.ACC_ABSTRACT;
    }

    ClassSymbol superclass;
    if (decl.xtnds().isPresent()) {
      superclass = resolveClass(decl.xtnds().get());
    } else {
      switch (decl.tykind()) {
        case ENUM:
          superclass = ClassSymbol.ENUM;
          // Assuming all enums are final is safe, because nothing outside
          // the compilation unit can extend abstract enums anyways, and
          // refactoring an existing enum to implement methods in the container
          // class instead of the constants is not a breaking change.
          access |= TurbineFlag.ACC_FINAL;
          break;
        case INTERFACE:
        case ANNOTATION:
        case CLASS:
          superclass = !origin.equals(ClassSymbol.OBJECT) ? ClassSymbol.OBJECT : null;
          break;
        default:
          throw new AssertionError(decl.tykind());
      }
    }

    ImmutableList.Builder<ClassSymbol> interfaces = ImmutableList.builder();
    if (!decl.impls().isEmpty()) {
      for (Tree.ClassTy i : decl.impls()) {
        ClassSymbol result = resolveClass(i);
        if (result == null) {
          throw new AssertionError(i);
        }
        interfaces.add(result);
      }
    } else {
      if (decl.tykind() == TurbineTyKind.ANNOTATION) {
        interfaces.add(ClassSymbol.ANNOTATION);
      }
    }

    ImmutableMap.Builder<String, TyVarSymbol> typeParameters = ImmutableMap.builder();
    for (Tree.TyParam p : decl.typarams()) {
      typeParameters.put(p.name(), new TyVarSymbol(origin, p.name()));
    }

    return new SourceHeaderBoundClass(
        base, superclass, interfaces.build(), access, typeParameters.build());
  }

  /**
   * Nested enums, interfaces, and annotations, and any types nested within interfaces and
   * annotations (JLS 9.5) are implicitly static.
   */
  private boolean implicitStatic(BoundClass c) {
    if (c.owner() == null) {
      return false;
    }
    switch (c.kind()) {
      case INTERFACE:
      case ENUM:
      case ANNOTATION:
        return true;
      case CLASS:
        switch (env.get(c.owner()).kind()) {
          case INTERFACE:
          case ANNOTATION:
            return true;
          default:
            return false;
        }
      default:
        throw new AssertionError(c.kind());
    }
  }

  /** Returns true if the given type is declared in an interface. */
  private boolean enclosedByInterface(BoundClass c) {
    if (c.owner() != null) {
      HeaderBoundClass info = env.get(c.owner());
      switch (info.kind()) {
        case INTERFACE:
        case ANNOTATION:
          return true;
        default:
          break;
      }
    }
    return false;
  }

  /**
   * Resolves the {@link ClassSymbol} for the given {@link Tree.ClassTy}, with handling for
   * non-canonical qualified type names.
   */
  private ClassSymbol resolveClass(Tree.ClassTy ty) {
    // flatten a left-recursive qualified type name to its component simple names
    // e.g. Foo<Bar>.Baz -> ["Foo", "Bar"]
    ArrayDeque<String> flat = new ArrayDeque<>();
    for (Tree.ClassTy curr = ty; curr != null; curr = curr.base().orNull()) {
      flat.addFirst(curr.name());
    }
    // Resolve the base symbol in the qualified name.
    LookupResult result = lookup(new LookupKey(flat));
    if (result == null) {
      throw TurbineError.format(base.source(), ty.position(), ErrorKind.SYMBOL_NOT_FOUND, ty);
    }
    // Resolve pieces in the qualified name referring to member types.
    // This needs to consider member type declarations inherited from supertypes and interfaces.
    ClassSymbol sym = (ClassSymbol) result.sym();
    for (String bit : result.remaining()) {
      sym = Resolve.resolve(env, origin, sym, bit);
      if (sym == null) {
        throw error(ty.position(), ErrorKind.SYMBOL_NOT_FOUND, bit);
      }
    }
    return sym;
  }

  /** Resolve a qualified type name to a symbol. */
  private LookupResult lookup(LookupKey lookup) {
    // Handle any lexically enclosing class declarations (if we're binding a member class).
    // We could build out scopes for this, but it doesn't seem worth it. (And sharing the scopes
    // with other members of the same enclosing declaration would be complicated.)
    for (ClassSymbol curr = base.owner(); curr != null; curr = env.get(curr).owner()) {
      ClassSymbol result = Resolve.resolve(env, origin, curr, lookup.first());
      if (result != null) {
        return new LookupResult(result, lookup);
      }
    }
    // Fall back to the top-level scopes for the compilation unit (imports, same package, then
    // qualified name resolution).
    return base.scope().lookup(lookup);
  }

  private TurbineError error(int position, ErrorKind kind, Object... args) {
    return TurbineError.format(base.source(), position, kind, args);
  }
}
