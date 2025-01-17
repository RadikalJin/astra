package org.alfasoftware.astra.core.refactoring.operations.imports;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.alfasoftware.astra.core.utils.ASTOperation;
import org.alfasoftware.astra.core.utils.AstraUtils;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodRef;
import org.eclipse.jdt.core.dom.MethodRefParameter;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.text.edits.MalformedTreeException;

/**
 * Removes unused imports.
 *
 * This refactor can be run standalone, but will also be run as part of cleanup after any operation has altered a file.
 *
 * This refactor currently loses comments that are on the import lines e.g. // NOPMD.
 */
public class UnusedImportRefactor implements ASTOperation {

  @Override
  public void run(CompilationUnit compilationUnit, ASTNode node, ASTRewrite rewriter)
      throws IOException, MalformedTreeException, BadLocationException {

    if (node instanceof TypeDeclaration) {
      // Only remove imports for top-level types
      TypeDeclaration typeDeclaration = (TypeDeclaration) node;
      if (! typeDeclaration.resolveBinding().isNested()) {
        ReferenceTrackingVisitor visitor = new ReferenceTrackingVisitor();
        compilationUnit.accept(visitor);
        Set<String> existingImports = new HashSet<>();

        for (Object obj : compilationUnit.imports()) {
          ImportDeclaration importDeclaration = (ImportDeclaration) obj;

          // remove duplicates
          if (existingImports.contains(importDeclaration.getName().toString())) {
            AstraUtils.removeImport(compilationUnit, importDeclaration, rewriter);
            continue;
          } else {
            existingImports.add(importDeclaration.getName().toString());
          }

          // Can't easily tell if on-demand imports are actually needed so best to leave them in place.
          if (importDeclaration.isOnDemand()) {
            continue;
          }

          // remove unused imports
          // TODO this doesn't properly handle static imports yet - they are quite problematic as you can't accurately resolve the method signature
          // (can be multiple methods with same name)
          if (! visitor.types.contains(AstraUtils.getSimpleName(importDeclaration.getName().toString()))) {
            AstraUtils.removeImport(compilationUnit, importDeclaration, rewriter);
            continue;
          }

          // remove imports for types in the same package
          if (compilationUnit.getPackage().getName().toString().equals(
            AstraUtils.getPackageName(importDeclaration.getName().toString()))
            && !AstraUtils.isImportOfInnerType(importDeclaration)) {
            AstraUtils.removeImport(compilationUnit, importDeclaration, rewriter);
            continue;
          }

          // remove non-static imports from java.lang - they don't need to be imported
          if (! importDeclaration.isStatic() && importDeclaration.getName().toString().split("java\\.lang\\.[A-Z]").length > 1
              && !AstraUtils.isImportOfInnerType(importDeclaration)) {
            AstraUtils.removeImport(compilationUnit, importDeclaration, rewriter);
            continue;
          }
        }
      }
    }

    final ListRewrite importListRewrite = rewriter.getListRewrite(compilationUnit, CompilationUnit.IMPORTS_PROPERTY);
    @SuppressWarnings("unchecked")
    List<ImportDeclaration> currentList = importListRewrite.getRewrittenList();

    // clear down existing list
    currentList.forEach(i -> {
      importListRewrite.remove(i, null);
    });

    // Sort the imports
    List<ImportDeclaration> sortedImports =
        Stream.concat(
          // Static imports are sorted by name alone
          currentList.stream()
          .filter(ImportDeclaration::isStatic)
          .sorted(Comparator.comparing(i -> i.getName().toString())),
          // Non-static imports are sorted with java, javax and org packages first, then package name, then simple name
          currentList.stream()
          .filter(i -> ! i.isStatic())
          .sorted(Comparator
            .comparing((ImportDeclaration i) -> ! AstraUtils.getPackageName(i.getName().toString()).startsWith("java."))
            .thenComparing(i -> ! AstraUtils.getPackageName(i.getName().toString()).startsWith("javax."))
            .thenComparing(i -> ! AstraUtils.getPackageName(i.getName().toString()).startsWith("org."))
            .thenComparing(i -> AstraUtils.getPackageName(i.getName().toString()))
            .thenComparing(i -> i.getName().toString()))
            )
        // filter out blank line separators
        .filter(i -> !i.getName().toString().equals("MISSING.MISSING"))
        .collect(Collectors.toList());


    // Add in blank line separators, if needed
    List<ImportDeclaration> newList = new ArrayList<>();
    for (int i = 0; i < sortedImports.size(); i++) {
      newList.add(sortedImports.get(i));
      if (sortedImports.size() > i + 1) {
        // Don't put separators between static methods
        if (sortedImports.get(i).isStatic() && sortedImports.get(i + 1).isStatic()) {
          continue;
        }

        // Add blank line separators between:
        // - the static and non-static imports
        // - imports starting with java. and others
        // - imports starting with a different first letter
        if (sortedImports.get(i).isStatic() != sortedImports.get(i + 1).isStatic() ||
            sortedImports.get(i).getName().toString().startsWith("java.") != sortedImports.get(i + 1).getName().toString().startsWith("java.") ||
            sortedImports.get(i).getName().toString().charAt(0) != sortedImports.get(i + 1).getName().toString().charAt(0)) {
          ASTNode placeholder = rewriter.createStringPlaceholder("", ASTNode.IMPORT_DECLARATION);
          newList.add((ImportDeclaration) placeholder);
        }
      }
    }

    // Write in the (now sorted) imports with blank line separators
    for (int i = 0; i < newList.size(); i++) {
      importListRewrite.insertAt(newList.get(i), i, null);
    }
  }


  private class ReferenceTrackingVisitor extends ASTVisitor {
    private final Set<String> types = new HashSet<>();

    @Override
    public boolean visit(SimpleName node) {
      if (! isInImport(node)) {
        types.add(AstraUtils.getSimpleName(node.toString()));
      }
      return super.visit(node);
    }

    private boolean isInImport(SimpleName name) {
      ASTNode currentNode = name;
      while (currentNode.getParent() != null) {
        currentNode = currentNode.getParent();
        if (currentNode instanceof ImportDeclaration) {
          return true;
        }
      }
      return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean visit(Javadoc node) {
      for(TagElement element : (List<TagElement>)node.tags()) {
        visitTagElement(element);
      }
      return super.visit(node);
    }

    private void visitTagElement(TagElement element) {
      for (Object fragment : element.fragments()) {
        if (fragment instanceof TagElement) {
          visitTagElement((TagElement) fragment);
        } else if (fragment instanceof SimpleName) {
          types.add(AstraUtils.getSimpleName(((SimpleName) fragment).toString()));
        } else if (fragment instanceof MethodRef) {
          visitJavadocMethodRef((MethodRef) fragment);
        }
      }
    }

    @SuppressWarnings("unchecked")
    private void visitJavadocMethodRef(MethodRef methodRef) {
      if (methodRef.getQualifier() != null) {
        types.add(AstraUtils.getSimpleName(methodRef.getQualifier().toString()));
      }
      for (MethodRefParameter param : (List<MethodRefParameter>)methodRef.parameters()) {
        types.add(param.getType().toString());
      }
    }
  }
}
