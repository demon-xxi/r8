// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.conversion;

import static com.android.tools.r8.ir.desugar.InterfaceMethodRewriter.Flavor.ExcludeDexResources;
import static com.android.tools.r8.ir.desugar.InterfaceMethodRewriter.Flavor.IncludeAllResources;
import static com.android.tools.r8.ir.optimize.CodeRewriter.checksNullBeforeSideEffect;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexApplication.Builder;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.ir.analysis.TypeChecker;
import com.android.tools.r8.ir.analysis.constant.SparseConditionalConstantPropagation;
import com.android.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.android.tools.r8.ir.code.AlwaysMaterializingDefinition;
import com.android.tools.r8.ir.code.AlwaysMaterializingUser;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.desugar.CovariantReturnTypeAnnotationTransformer;
import com.android.tools.r8.ir.desugar.InterfaceMethodRewriter;
import com.android.tools.r8.ir.desugar.Java8MethodRewriter;
import com.android.tools.r8.ir.desugar.LambdaRewriter;
import com.android.tools.r8.ir.desugar.StringConcatRewriter;
import com.android.tools.r8.ir.desugar.TwrCloseResourceRewriter;
import com.android.tools.r8.ir.optimize.CodeRewriter;
import com.android.tools.r8.ir.optimize.ConstantCanonicalizer;
import com.android.tools.r8.ir.optimize.DeadCodeRemover;
import com.android.tools.r8.ir.optimize.Devirtualizer;
import com.android.tools.r8.ir.optimize.IdempotentFunctionCallCanonicalizer;
import com.android.tools.r8.ir.optimize.Inliner;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import com.android.tools.r8.ir.optimize.MemberValuePropagation;
import com.android.tools.r8.ir.optimize.NonNullTracker;
import com.android.tools.r8.ir.optimize.Outliner;
import com.android.tools.r8.ir.optimize.PeepholeOptimizer;
import com.android.tools.r8.ir.optimize.RedundantFieldLoadElimination;
import com.android.tools.r8.ir.optimize.ReflectionOptimizer;
import com.android.tools.r8.ir.optimize.UninstantiatedTypeOptimization;
import com.android.tools.r8.ir.optimize.classinliner.ClassInliner;
import com.android.tools.r8.ir.optimize.lambda.LambdaMerger;
import com.android.tools.r8.ir.optimize.staticizer.ClassStaticizer;
import com.android.tools.r8.ir.optimize.string.StringOptimizer;
import com.android.tools.r8.ir.regalloc.LinearScanRegisterAllocator;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.android.tools.r8.kotlin.KotlinInfo;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.naming.IdentifierNameStringMarker;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.shaking.MainDexClasses;
import com.android.tools.r8.shaking.RootSetBuilder.RootSet;
import com.android.tools.r8.utils.CfgPrinter;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.InternalOptions.OutlineOptions;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class IRConverter {

  private static final int PEEPHOLE_OPTIMIZATION_PASSES = 2;

  public final AppInfo appInfo;
  public final AppView<? extends AppInfoWithSubtyping> appView;
  public final Set<DexType> mainDexClasses;
  public final RootSet rootSet;

  private final Timing timing;
  private final Outliner outliner;
  private final StringConcatRewriter stringConcatRewriter;
  private final LambdaRewriter lambdaRewriter;
  private final InterfaceMethodRewriter interfaceMethodRewriter;
  private final TwrCloseResourceRewriter twrCloseResourceRewriter;
  private final Java8MethodRewriter java8MethodRewriter;
  private final LambdaMerger lambdaMerger;
  private final ClassInliner classInliner;
  private final ClassStaticizer classStaticizer;
  private final InternalOptions options;
  private final CfgPrinter printer;
  private final CodeRewriter codeRewriter;
  private final MemberValuePropagation memberValuePropagation;
  private final LensCodeRewriter lensCodeRewriter;
  private final NonNullTracker nonNullTracker;
  private final Inliner inliner;
  private final IdentifierNameStringMarker identifierNameStringMarker;
  private final Devirtualizer devirtualizer;
  private final CovariantReturnTypeAnnotationTransformer covariantReturnTypeAnnotationTransformer;
  private final StringOptimizer stringOptimizer;
  private final UninstantiatedTypeOptimization uninstantiatedTypeOptimization;
  private final TypeChecker typeChecker;
  private final IdempotentFunctionCallCanonicalizer idempotentFunctionCallCanonicalizer;

  final DeadCodeRemover deadCodeRemover;

  public final boolean enableWholeProgramOptimizations;

  private final OptimizationFeedbackDelayed delayedOptimizationFeedback =
      new OptimizationFeedbackDelayed();
  private final OptimizationFeedback ignoreOptimizationFeedback = new OptimizationFeedbackIgnore();
  private final OptimizationFeedback simpleOptimizationFeedback = new OptimizationFeedbackSimple();
  private DexString highestSortingString;

  // For some optimizations, e.g. optimizing synthetic classes, we may need to resolve
  // the current class being optimized.
  private ConcurrentHashMap<DexType, DexProgramClass> cachedClasses = new ConcurrentHashMap<>();

  // The argument `appView` is only available when full program optimizations are allowed
  // (i.e., when running R8).
  private IRConverter(
      AppInfo appInfo,
      InternalOptions options,
      Timing timing,
      CfgPrinter printer,
      AppView<? extends AppInfoWithSubtyping> appView,
      MainDexClasses mainDexClasses,
      RootSet rootSet) {
    assert appInfo != null;
    assert options != null;
    assert options.programConsumer != null;
    assert appView == null
        || appView.appInfo().hasLiveness()
        || appView.graphLense().isIdentityLense();
    this.timing = timing != null ? timing : new Timing("internal");
    this.appInfo = appInfo;
    this.appView = appView;
    this.rootSet = rootSet;
    this.options = options;
    this.printer = printer;
    this.mainDexClasses = mainDexClasses.getClasses();
    this.codeRewriter = new CodeRewriter(this, libraryMethodsReturningReceiver(), options);
    this.stringConcatRewriter = new StringConcatRewriter(appInfo);
    this.lambdaRewriter = options.enableDesugaring ? new LambdaRewriter(this) : null;
    this.interfaceMethodRewriter =
        (options.enableDesugaring && enableInterfaceMethodDesugaring())
            ? new InterfaceMethodRewriter(this, options) : null;
    this.twrCloseResourceRewriter =
        (options.enableDesugaring && enableTwrCloseResourceDesugaring())
            ? new TwrCloseResourceRewriter(this) : null;
    this.java8MethodRewriter =
        (options.enableDesugaring && !options.canUseJava8Methods())
            ? new Java8MethodRewriter(this) : null;
    this.lambdaMerger =
        options.enableLambdaMerging ? new LambdaMerger(appInfo, options.reporter) : null;
    this.covariantReturnTypeAnnotationTransformer =
        options.processCovariantReturnTypeAnnotations
            ? new CovariantReturnTypeAnnotationTransformer(this, appInfo.dexItemFactory)
            : null;
    this.stringOptimizer = new StringOptimizer(appInfo, options.getInternalOutputMode());
    this.enableWholeProgramOptimizations = appView != null;
    if (enableWholeProgramOptimizations) {
      assert appInfo.hasLiveness();
      AppInfoWithLiveness appInfoWithLiveness = appInfo.withLiveness();
      AppView<? extends AppInfoWithLiveness> appViewWithLiveness = appView.withLiveness();
      assert rootSet != null;
      this.nonNullTracker = new NonNullTracker(
          appInfoWithLiveness, libraryMethodsReturningNonNull(appInfo.dexItemFactory));
      this.inliner = new Inliner(appViewWithLiveness, this, options, mainDexClasses);
      this.outliner = new Outliner(appInfoWithLiveness, options, this);
      this.memberValuePropagation =
          options.enableValuePropagation ? new MemberValuePropagation(appInfoWithLiveness) : null;
      this.lensCodeRewriter = new LensCodeRewriter(appView, options);
      if (!appInfoWithLiveness.identifierNameStrings.isEmpty() && options.enableMinification) {
        this.identifierNameStringMarker =
            new IdentifierNameStringMarker(appInfoWithLiveness, options);
      } else {
        this.identifierNameStringMarker = null;
      }
      this.devirtualizer =
          options.enableDevirtualization ? new Devirtualizer(appViewWithLiveness) : null;
      this.uninstantiatedTypeOptimization =
          options.enableUninstantiatedTypeOptimization
              ? new UninstantiatedTypeOptimization(appViewWithLiveness, options)
              : null;
      this.typeChecker = new TypeChecker(appView);
    } else {
      this.nonNullTracker = null;
      this.inliner = null;
      this.outliner = null;
      this.memberValuePropagation = null;
      this.lensCodeRewriter = null;
      this.identifierNameStringMarker = null;
      this.devirtualizer = null;
      this.uninstantiatedTypeOptimization = null;
      this.typeChecker = null;
    }
    this.classInliner =
        (options.enableClassInlining && options.enableInlining && inliner != null)
            ? new ClassInliner(
            appInfo.dexItemFactory, lambdaRewriter, options.classInliningInstructionLimit)
            : null;
    this.classStaticizer = options.enableClassStaticizer && appInfo.hasLiveness()
        ? new ClassStaticizer(appInfo.withLiveness(), this) : null;
    this.deadCodeRemover =
        new DeadCodeRemover(
            appInfo, codeRewriter, graphLense(), options, enableWholeProgramOptimizations);
    this.idempotentFunctionCallCanonicalizer =
        new IdempotentFunctionCallCanonicalizer(appInfo.dexItemFactory);
  }

  public GraphLense graphLense() {
    return appView != null ? appView.graphLense() : GraphLense.getIdentityLense();
  }

  public Set<DexCallSite> getDesugaredCallSites() {
    if (lambdaRewriter != null) {
      return lambdaRewriter.getDesugaredCallSites();
    } else {
      return Collections.emptySet();
    }
  }

  /**
   * Create an IR converter for processing methods with full program optimization disabled.
   */
  public IRConverter(AppInfo appInfo, InternalOptions options) {
    this(appInfo, options, null, null, null, MainDexClasses.NONE, null);
  }

  /**
   * Create an IR converter for processing methods with full program optimization disabled.
   */
  public IRConverter(AppInfo appInfo, InternalOptions options, Timing timing, CfgPrinter printer) {
    this(appInfo, options, timing, printer, null, MainDexClasses.NONE, null);
  }

  /**
   * Create an IR converter for processing methods with full program optimization enabled.
   */
  public IRConverter(
      AppView<AppInfoWithSubtyping> appView,
      InternalOptions options,
      Timing timing,
      CfgPrinter printer,
      MainDexClasses mainDexClasses,
      RootSet rootSet) {
    this(appView.appInfo(), options, timing, printer, appView, mainDexClasses, rootSet);
  }

  private boolean enableInterfaceMethodDesugaring() {
    switch (options.interfaceMethodDesugaring) {
      case Off:
        return false;
      case Auto:
        return !options.canUseDefaultAndStaticInterfaceMethods();
    }
    throw new Unreachable();
  }

  private boolean enableTwrCloseResourceDesugaring() {
    return enableTryWithResourcesDesugaring() && !options.canUseTwrCloseResourceMethod();
  }

  private boolean enableTryWithResourcesDesugaring() {
    switch (options.tryWithResourcesDesugaring) {
      case Off:
        return false;
      case Auto:
        return !options.canUseSuppressedExceptions();
    }
    throw new Unreachable();
  }

  private Set<DexMethod> libraryMethodsReturningReceiver() {
    Set<DexMethod> methods = new HashSet<>();
    DexItemFactory dexItemFactory = appInfo.dexItemFactory;
    dexItemFactory.stringBufferMethods.forEachAppendMethod(methods::add);
    dexItemFactory.stringBuilderMethods.forEachAppendMethod(methods::add);
    return methods;
  }

  // Library methods listed here are based on their original implementations. That is, we assume
  // these cannot be overridden.
  public static Set<DexMethod> libraryMethodsReturningNonNull(DexItemFactory factory) {
    return ImmutableSet.of(
        factory.stringMethods.valueOf,
        factory.classMethods.getName,
        factory.classMethods.getSimpleName
    );
  }

  private void removeLambdaDeserializationMethods() {
    if (lambdaRewriter != null) {
      lambdaRewriter.removeLambdaDeserializationMethods(appInfo.classes());
    }
  }

  private void synthesizeLambdaClasses(Builder<?> builder, ExecutorService executorService)
      throws ExecutionException {
    if (lambdaRewriter != null) {
      lambdaRewriter.adjustAccessibility();
      lambdaRewriter.synthesizeLambdaClasses(builder, executorService);
    }
  }

  private Set<DexEncodedMethod> staticizeClasses(
      OptimizationFeedback feedback, ExecutorService executorService) throws ExecutionException {
    if (classStaticizer != null) {
      return classStaticizer.staticizeCandidates(feedback, executorService);
    }
    return ImmutableSet.of();
  }

  private void collectStaticizerCandidates(DexApplication application) {
    if (classStaticizer != null) {
      classStaticizer.collectCandidates(application);
    }
  }

  private void desugarInterfaceMethods(
      Builder<?> builder,
      InterfaceMethodRewriter.Flavor includeAllResources,
      ExecutorService executorService)
      throws ExecutionException {
    desugarInterfaceMethods(builder, includeAllResources, executorService, null);
  }

  private void desugarInterfaceMethods(
      Builder<?> builder,
      InterfaceMethodRewriter.Flavor includeAllResources,
      ExecutorService executorService,
      Map<DexType, DexProgramClass> synthesizedClasses)
      throws ExecutionException {
    if (interfaceMethodRewriter != null) {
      interfaceMethodRewriter.desugarInterfaceMethods(
          builder, includeAllResources, executorService, synthesizedClasses);
    }
  }

  private void synthesizeTwrCloseResourceUtilityClass(Builder<?> builder) {
    if (twrCloseResourceRewriter != null) {
      twrCloseResourceRewriter.synthesizeUtilityClass(builder, options);
    }
  }

  private void synthesizeJava8UtilityClass(Builder<?> builder) {
    if (java8MethodRewriter != null) {
      java8MethodRewriter.synthesizeUtilityClass(builder, options);
    }
  }

  private void processCovariantReturnTypeAnnotations(Builder<?> builder) {
    if (covariantReturnTypeAnnotationTransformer != null) {
      covariantReturnTypeAnnotationTransformer.process(builder);
    }
  }

  public DexApplication convertToDex(DexApplication application, ExecutorService executor)
      throws ExecutionException {
    removeLambdaDeserializationMethods();

    timing.begin("IR conversion");
    convertClassesToDex(application.classes(), executor);

    // Build a new application with jumbo string info,
    Builder<?> builder = application.builder();
    builder.setHighestSortingString(highestSortingString);

    synthesizeLambdaClasses(builder, executor);
    desugarInterfaceMethods(builder, ExcludeDexResources, executor);
    synthesizeTwrCloseResourceUtilityClass(builder);
    synthesizeJava8UtilityClass(builder);
    processCovariantReturnTypeAnnotations(builder);

    handleSynthesizedClassMapping(builder);
    timing.end();

    return builder.build();
  }

  private void handleSynthesizedClassMapping(Builder<?> builder) {
    if (options.intermediate) {
      updateSynthesizedClassMapping(builder);
    }

    updateMainDexListWithSynthesizedClassMap(builder);

    if (!options.intermediate) {
      clearSynthesizedClassMapping(builder);
    }
  }

  private void updateMainDexListWithSynthesizedClassMap(Builder<?> builder) {
    Set<DexType> inputMainDexList = builder.getMainDexList();
    if (!inputMainDexList.isEmpty()) {
      Map<DexType, DexProgramClass> programClasses = builder.getProgramClasses().stream()
          .collect(Collectors.toMap(
              programClass -> programClass.type,
              Function.identity()));
      Collection<DexType> synthesized = new ArrayList<>();
      for (DexType dexType : inputMainDexList) {
        DexProgramClass programClass = programClasses.get(dexType);
        if (programClass != null) {
          synthesized.addAll(DexAnnotation.readAnnotationSynthesizedClassMap(
              programClass, builder.dexItemFactory));
        }
      }
      builder.addToMainDexList(synthesized);
    }
  }

  private void clearSynthesizedClassMapping(Builder<?> builder) {
    for (DexProgramClass programClass : builder.getProgramClasses()) {
      programClass.annotations =
          programClass.annotations.getWithout(builder.dexItemFactory.annotationSynthesizedClassMap);
    }
  }

  private void updateSynthesizedClassMapping(Builder<?> builder) {
    ListMultimap<DexProgramClass, DexProgramClass> originalToSynthesized =
        ArrayListMultimap.create();
    for (DexProgramClass synthesized : builder.getSynthesizedClasses()) {
      for (DexProgramClass original : synthesized.getSynthesizedFrom()) {
        originalToSynthesized.put(original, synthesized);
      }
    }

    for (Map.Entry<DexProgramClass, Collection<DexProgramClass>> entry :
        originalToSynthesized.asMap().entrySet()) {
      DexProgramClass original = entry.getKey();
      // Use a tree set to make sure that we have an ordering on the types.
      // These types are put in an array in annotations in the output and we
      // need a consistent ordering on them.
      TreeSet<DexType> synthesized = new TreeSet<>(DexType::slowCompareTo);
      entry.getValue()
          .stream()
          .map(dexProgramClass -> dexProgramClass.type)
          .forEach(synthesized::add);
      synthesized.addAll(
          DexAnnotation.readAnnotationSynthesizedClassMap(original, builder.dexItemFactory));

      DexAnnotation updatedAnnotation =
          DexAnnotation.createAnnotationSynthesizedClassMap(synthesized, builder.dexItemFactory);

      original.annotations = original.annotations.getWithAddedOrReplaced(updatedAnnotation);
    }
  }

  private void convertClassesToDex(Iterable<DexProgramClass> classes,
      ExecutorService executor) throws ExecutionException {
    List<Future<?>> futures = new ArrayList<>();
    for (DexProgramClass clazz : classes) {
      futures.add(executor.submit(() -> convertMethodsToDex(clazz)));
    }
    ThreadUtils.awaitFutures(futures);
  }

  private void convertMethodsToDex(DexProgramClass clazz) {
    boolean isReachabilitySensitive = clazz.hasReachabilitySensitiveAnnotation(options.itemFactory);
    // When converting all methods on a class always convert <clinit> first.
    for (DexEncodedMethod method : clazz.directMethods()) {
      if (method.isClassInitializer()) {
        method.getMutableOptimizationInfo().setReachabilitySensitive(isReachabilitySensitive);
        convertMethodToDex(method);
        break;
      }
    }
    clazz.forEachMethod(
        method -> {
          if (!method.isClassInitializer()) {
            method.getMutableOptimizationInfo().setReachabilitySensitive(isReachabilitySensitive);
            convertMethodToDex(method);
          }
        });
  }

  private void convertMethodToDex(DexEncodedMethod method) {
    assert options.isGeneratingDex();
    if (method.getCode() != null) {
      boolean matchesMethodFilter = options.methodMatchesFilter(method);
      if (matchesMethodFilter) {
        if (!(options.passthroughDexCode && method.getCode().isDexCode())) {
          // We do not process in call graph order, so anything could be a leaf.
          rewriteCode(method, simpleOptimizationFeedback, x -> true, CallSiteInformation.empty(),
              Outliner::noProcessing);
        }
        updateHighestSortingStrings(method);
      }
    }
  }

  public DexApplication optimize(DexApplication application) throws ExecutionException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      return optimize(application, executor);
    } finally {
      executor.shutdown();
    }
  }

  public DexApplication optimize(DexApplication application, ExecutorService executorService)
      throws ExecutionException {
    computeReachabilitySensitivity(application);
    removeLambdaDeserializationMethods();
    collectLambdaMergingCandidates(application);
    collectStaticizerCandidates(application);

    // The process is in two phases.
    // 1) Subject all DexEncodedMethods to optimization (except outlining).
    //    - a side effect is candidates for outlining are identified.
    // 2) Perform outlining for the collected candidates.
    // Ideally, we should outline eagerly when threshold for a template has been reached.

    printPhase("Primary optimization pass");

    // Process the application identifying outlining candidates.
    GraphLense graphLenseForIR = graphLense();
    OptimizationFeedbackDelayed feedback = delayedOptimizationFeedback;
    {
      timing.begin("Build call graph");
      CallGraph callGraph = CallGraph.build(application, appView.withLiveness(), options, timing);
      timing.end();
      timing.begin("IR conversion phase 1");
      BiConsumer<IRCode, DexEncodedMethod> outlineHandler =
          outliner == null ? Outliner::noProcessing : outliner.identifyCandidateMethods();
      callGraph.forEachMethod(
          (method, isProcessedConcurrently) ->
              processMethod(method, feedback, isProcessedConcurrently, callGraph, outlineHandler),
          feedback::updateVisibleOptimizationInfo,
          executorService);
      timing.end();
      assert graphLenseForIR == graphLense();
    }

    // Second inlining pass for dealing with double inline callers.
    if (inliner != null) {
      printPhase("Double caller inlining");
      assert graphLenseForIR == graphLense();
      inliner.processDoubleInlineCallers(this, feedback);
      feedback.updateVisibleOptimizationInfo();
      assert graphLenseForIR == graphLense();
    }

    // TODO(b/112831361): Implement support for staticizeClasses in CF backend.
    if (!options.isGeneratingClassFiles()) {
      printPhase("Class staticizer post processing");
      staticizeClasses(feedback, executorService);
    }

    // Build a new application with jumbo string info.
    Builder<?> builder = application.builder();
    builder.setHighestSortingString(highestSortingString);

    printPhase("Lambda class synthesis");
    synthesizeLambdaClasses(builder, executorService);

    printPhase("Interface method desugaring");
    Map<DexType, DexProgramClass> synthesizedClasses = new IdentityHashMap<>();
    desugarInterfaceMethods(builder, IncludeAllResources, executorService, synthesizedClasses);

    printPhase("Twr close resource utility class synthesis");
    synthesizeTwrCloseResourceUtilityClass(builder);
    synthesizeJava8UtilityClass(builder);
    handleSynthesizedClassMapping(builder);

    printPhase("Lambda merging finalization");
    finalizeLambdaMerging(application, feedback, builder, executorService, synthesizedClasses);

    if (outliner != null) {
      printPhase("Outlining");
      timing.begin("IR conversion phase 2");
      if (outliner.selectMethodsForOutlining(synthesizedClasses)) {
        forEachSelectedOutliningMethod(
            executorService,
            (code, method) -> {
              printMethod(code, "IR before outlining (SSA)", null);
              outliner.identifyOutlineSites(code, method);
            });
        DexProgramClass outlineClass = outliner.buildOutlinerClass(computeOutlineClassType());
        optimizeSynthesizedClass(outlineClass);
        forEachSelectedOutliningMethod(
            executorService,
            (code, method) -> {
              outliner.applyOutliningCandidate(code, method);
              printMethod(code, "IR after outlining (SSA)", null);
              finalizeIR(method, code, ignoreOptimizationFeedback);
            });
        assert outliner.checkAllOutlineSitesFoundAgain();
        builder.addSynthesizedClass(outlineClass, true);
        clearDexMethodCompilationState(outlineClass);
      }
      timing.end();
    }
    clearDexMethodCompilationState();

    if (identifierNameStringMarker != null) {
      identifierNameStringMarker.decoupleIdentifierNameStringsInFields();
    }

    if (Log.ENABLED && uninstantiatedTypeOptimization != null) {
      uninstantiatedTypeOptimization.logResults();
    }

    return builder.build();
  }

  private void computeReachabilitySensitivity(DexApplication application) {
    application.classes().forEach(c -> {
      if (c.hasReachabilitySensitiveAnnotation(options.itemFactory)) {
        c.methods().forEach(m -> m.getMutableOptimizationInfo().setReachabilitySensitive(true));
      }
    });
  }

  private void forEachSelectedOutliningMethod(
      ExecutorService executorService, BiConsumer<IRCode, DexEncodedMethod> consumer)
      throws ExecutionException {
    assert appView != null;
    assert !options.skipIR;
    Set<DexEncodedMethod> methods = outliner.getMethodsSelectedForOutlining();
    List<Future<?>> futures = new ArrayList<>();
    for (DexEncodedMethod method : methods) {
      futures.add(
          executorService.submit(
              () -> {
                IRCode code =
                    method.buildIR(
                        appInfo,
                        appView.graphLense(),
                        options,
                        appInfo.originFor(method.method.holder));
                assert code != null;
                assert !method.getCode().isOutlineCode();
                // Instead of repeating all the optimizations of rewriteCode(), only run the
                // optimizations needed for outlining: rewriteMoveResult() to remove out-values on
                // StringBuilder/StringBuffer method invocations, and removeDeadCode() to remove
                // unused out-values.
                codeRewriter.rewriteMoveResult(code);
                deadCodeRemover.run(code);
                consumer.accept(code, method);
                return null;
              }));
    }
    ThreadUtils.awaitFutures(futures);
  }

  private void collectLambdaMergingCandidates(DexApplication application) {
    if (lambdaMerger != null) {
      lambdaMerger.collectGroupCandidates(application, appInfo.withLiveness(), options);
    }
  }

  private void finalizeLambdaMerging(
      DexApplication application,
      OptimizationFeedback directFeedback,
      Builder<?> builder,
      ExecutorService executorService,
      Map<DexType, DexProgramClass> synthesizedClasses)
      throws ExecutionException {
    if (lambdaMerger != null) {
      lambdaMerger.applyLambdaClassMapping(
          application, this, directFeedback, builder, executorService, synthesizedClasses);
    }
  }

  private void clearDexMethodCompilationState() {
    appInfo.classes().forEach(this::clearDexMethodCompilationState);
  }

  private void clearDexMethodCompilationState(DexProgramClass clazz) {
    clazz.forEachMethod(DexEncodedMethod::markNotProcessed);
  }

  /**
   * This will replace the Dex code in the method with the Dex code generated from the provided IR.
   * <p>
   * This method is *only* intended for testing, where tests manipulate the IR and need runnable Dex
   * code.
   *
   * @param method the method to replace code for
   * @param code the IR code for the method
   */
  public void replaceCodeForTesting(DexEncodedMethod method, IRCode code) {
    if (Log.ENABLED) {
      Log.debug(getClass(), "Initial (SSA) flow graph for %s:\n%s", method.toSourceString(), code);
    }
    assert code.isConsistentSSA();
    code.traceBlocks();
    RegisterAllocator registerAllocator = performRegisterAllocation(code, method);
    method.setCode(code, registerAllocator, options);
    if (Log.ENABLED) {
      Log.debug(getClass(), "Resulting dex code for %s:\n%s",
          method.toSourceString(), logCode(options, method));
    }
  }

  // Find an unused name for the outlining class. When multiple runs produces additional
  // outlining the default outlining class might already be present.
  private DexType computeOutlineClassType() {
    DexType result;
    int count = 0;
    do {
      String name = OutlineOptions.CLASS_NAME + (count == 0 ? "" : Integer.toString(count));
      count++;
      result = appInfo.dexItemFactory.createType(DescriptorUtils.javaTypeToDescriptor(name));
    } while (appInfo.definitionFor(result) != null);
    // Register the newly generated type in the subtyping hierarchy, if we have one.
    appInfo.registerNewType(result, appInfo.dexItemFactory.objectType);
    return result;
  }

  public DexClass definitionFor(DexType type) {
    DexProgramClass cached = cachedClasses.get(type);
    return cached != null ? cached : appInfo.definitionFor(type);
  }

  public void optimizeSynthesizedClass(DexProgramClass clazz) {
    try {
      enterCachedClass(clazz);
      // Process the generated class, but don't apply any outlining.
      clazz.forEachMethod(this::optimizeSynthesizedMethod);
    } finally {
      leaveCachedClass(clazz);
    }
  }

  public void optimizeSynthesizedClasses(
      Collection<DexProgramClass> classes, ExecutorService executorService)
      throws ExecutionException {
    Set<DexEncodedMethod> methods = Sets.newIdentityHashSet();
    try {
      for (DexProgramClass clazz : classes) {
        enterCachedClass(clazz);
        clazz.forEachMethod(methods::add);
      }
      // Process the generated class, but don't apply any outlining.
      optimizeSynthesizedMethods(methods, executorService);
    } finally {
      for (DexProgramClass clazz : classes) {
        leaveCachedClass(clazz);
      }
    }
  }

  public void optimizeMethodOnSynthesizedClass(DexProgramClass clazz, DexEncodedMethod method) {
    if (!method.isProcessed()) {
      try {
        enterCachedClass(clazz);
        // Process the generated method, but don't apply any outlining.
        optimizeSynthesizedMethod(method);
      } finally {
        leaveCachedClass(clazz);
      }
    }
  }

  public void optimizeSynthesizedMethod(DexEncodedMethod method) {
    if (!method.isProcessed()) {
      // Process the generated method, but don't apply any outlining.
      processMethod(
          method,
          delayedOptimizationFeedback,
          Predicates.alwaysFalse(),
          CallSiteInformation.empty(),
          Outliner::noProcessing);
    }
  }

  public void optimizeSynthesizedMethods(
      Collection<DexEncodedMethod> methods, ExecutorService executorService)
      throws ExecutionException {
    List<Future<?>> futures = new ArrayList<>();
    for (DexEncodedMethod method : methods) {
      futures.add(
          executorService.submit(
              () -> {
                processMethod(
                    method,
                    delayedOptimizationFeedback,
                    methods::contains,
                    CallSiteInformation.empty(),
                    Outliner::noProcessing);
                return null; // we want a Callable not a Runnable to be able to throw
              }));
    }
    ThreadUtils.awaitFutures(futures);
  }

  private void enterCachedClass(DexProgramClass clazz) {
    DexProgramClass previous = cachedClasses.put(clazz.type, clazz);
    assert previous == null;
  }

  private void leaveCachedClass(DexProgramClass clazz) {
    DexProgramClass existing = cachedClasses.remove(clazz.type);
    assert existing == clazz;
  }

  private String logCode(InternalOptions options, DexEncodedMethod method) {
    return options.useSmaliSyntax ? method.toSmaliString(null) : method.codeToString();
  }

  public void processMethod(
      DexEncodedMethod method,
      OptimizationFeedback feedback,
      Predicate<DexEncodedMethod> isProcessedConcurrently,
      CallSiteInformation callSiteInformation,
      BiConsumer<IRCode, DexEncodedMethod> outlineHandler) {
    Code code = method.getCode();
    boolean matchesMethodFilter = options.methodMatchesFilter(method);
    if (code != null && matchesMethodFilter) {
      rewriteCode(method, feedback, isProcessedConcurrently, callSiteInformation, outlineHandler);
    } else {
      // Mark abstract methods as processed as well.
      method.markProcessed(ConstraintWithTarget.NEVER);
    }
  }

  private static void invertConditionalsForTesting(IRCode code) {
    for (BasicBlock block : code.blocks) {
      if (block.exit().isIf()) {
        block.exit().asIf().invert();
      }
    }
  }

  private void rewriteCode(
      DexEncodedMethod method,
      OptimizationFeedback feedback,
      Predicate<DexEncodedMethod> isProcessedConcurrently,
      CallSiteInformation callSiteInformation,
      BiConsumer<IRCode, DexEncodedMethod> outlineHandler) {
    if (options.verbose) {
      options.reporter.info(
          new StringDiagnostic("Processing: " + method.toSourceString()));
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Original code for %s:\n%s",
          method.toSourceString(), logCode(options, method));
    }
    if (options.skipIR) {
      feedback.markProcessed(method, ConstraintWithTarget.NEVER);
      return;
    }
    IRCode code =
        method.buildIR(appInfo, graphLense(), options, appInfo.originFor(method.method.holder));
    if (code == null) {
      feedback.markProcessed(method, ConstraintWithTarget.NEVER);
      return;
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Initial (SSA) flow graph for %s:\n%s", method.toSourceString(), code);
    }
    // Compilation header if printing CFGs for this method.
    printC1VisualizerHeader(method);
    String previous = printMethod(code, "Initial IR (SSA)", null);

    if (options.testing.irModifier != null) {
      options.testing.irModifier.accept(code);
    }

    if (options.canHaveArtStringNewInitBug()) {
      CodeRewriter.ensureDirectStringNewToInit(code);
    }

    boolean isDebugMode = options.debug || method.getOptimizationInfo().isReachabilitySensitive();

    if (isDebugMode) {
      codeRewriter.simplifyDebugLocals(code);
    }

    if (!method.isProcessed()) {
      if (lensCodeRewriter != null) {
        lensCodeRewriter.rewrite(code, method);
      } else {
        assert graphLense().isIdentityLense();
      }
    }

    if (typeChecker != null && !typeChecker.check(code)) {
      assert enableWholeProgramOptimizations;
      assert options.testing.allowTypeErrors;
      StringDiagnostic warning =
          new StringDiagnostic(
              "The method `"
                  + method.toSourceString()
                  + "` does not type check and will be assumed to be unreachable.");
      options.reporter.warning(warning);
      finalizeEmptyThrowingCode(method, feedback);
      return;
    }

    // This is the first point in time where we can assert that the types are sound. If this
    // assert fails, then the types that we have inferred are unsound, or the method does not type
    // check. In the latter case, the type checker should be extended to detect the issue such that
    // we will return with finalizeEmptyThrowingCode() above.
    assert code.verifyTypes(appInfo, appView, graphLense());

    if (classStaticizer != null) {
      classStaticizer.fixupMethodCode(method, code);
      assert code.isConsistentSSA();
    }

    previous = printMethod(code, "IR after class staticizer (SSA)", previous);

    if (identifierNameStringMarker != null) {
      identifierNameStringMarker.decoupleIdentifierNameStringsInMethod(method, code);
      assert code.isConsistentSSA();
    }

    if (memberValuePropagation != null) {
      memberValuePropagation.rewriteWithConstantValues(
          code, method.method.holder, isProcessedConcurrently);
    }
    if (options.enableSwitchMapRemoval && appInfo.hasLiveness()) {
      codeRewriter.removeSwitchMaps(code);
    }
    if (options.disableAssertions) {
      codeRewriter.disableAssertions(appInfo, method, code, feedback);
    }

    previous = printMethod(code, "IR after disable assertions (SSA)", previous);

    if (options.enableNonNullTracking && nonNullTracker != null) {
      nonNullTracker.addNonNull(code);
      assert code.isConsistentSSA();
    }

    previous = printMethod(code, "IR after null tracking (SSA)", previous);

    if (!isDebugMode && options.enableInlining && inliner != null) {
      // TODO(zerny): Should we support inlining in debug mode? b/62937285
      inliner.performInlining(method, code, isProcessedConcurrently, callSiteInformation);
    }

    previous = printMethod(code, "IR after inlining (SSA)", previous);

    if (appInfo.hasLiveness()) {
      // Reflection optimization 1. getClass() -> const-class
      ReflectionOptimizer.rewriteGetClass(appInfo.withLiveness(), code);
    }

    if (!isDebugMode) {
      stringOptimizer.computeTrivialOperationsOnConstString(code);
      // Reflection optimization 2. get*Name() with const-class -> const-string
      if (options.enableNameReflectionOptimization
          || options.testing.forceNameReflectionOptimization) {
        stringOptimizer.rewriteClassGetName(code, rootSet);
      }
      // Reflection optimization 3. String#valueOf(const-string) -> no op.
      stringOptimizer.removeTrivialConversions(code);
      assert code.isConsistentSSA();
    }

    if (devirtualizer != null) {
      assert code.verifyTypes(appInfo, appView, graphLense());
      devirtualizer.devirtualizeInvokeInterface(code, method.method.getHolder());
    }
    if (uninstantiatedTypeOptimization != null) {
      uninstantiatedTypeOptimization.rewrite(method, code);
    }

    assert code.verifyTypes(appInfo, appView, graphLense());
    codeRewriter.removeTrivialCheckCastAndInstanceOfInstructions(
        code, enableWholeProgramOptimizations);

    codeRewriter.rewriteLongCompareAndRequireNonNull(code, options);
    codeRewriter.commonSubexpressionElimination(code);
    codeRewriter.simplifyArrayConstruction(code);
    codeRewriter.rewriteMoveResult(code);
    codeRewriter.splitRangeInvokeConstants(code);
    new SparseConditionalConstantPropagation(code).run();
    codeRewriter.rewriteSwitch(code);
    codeRewriter.processMethodsNeverReturningNormally(code);
    codeRewriter.simplifyIf(code);
    // TODO(b/123284765) This produces a runtime-crash in Q. Activate again when fixed.
    // codeRewriter.redundantConstNumberRemoval(code);
    new RedundantFieldLoadElimination(appInfo, code, enableWholeProgramOptimizations).run();

    if (options.testing.invertConditionals) {
      invertConditionalsForTesting(code);
    }

    if (options.enableNonNullTracking && nonNullTracker != null) {
      // Computation of non-null parameters on normal exits rely on the existence of non-null IRs.
      nonNullTracker.computeNonNullParamOnNormalExits(feedback, code);
      nonNullTracker.cleanupNonNull(code);
      assert code.isConsistentSSA();
    }

    if (!isDebugMode) {
      codeRewriter.collectClassInitializerDefaults(method, code);
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Intermediate (SSA) flow graph for %s:\n%s",
          method.toSourceString(), code);
    }
    // Dead code removal. Performed after simplifications to remove code that becomes dead
    // as a result of those simplifications. The following optimizations could reveal more
    // dead code which is removed right before register allocation in performRegisterAllocation.
    deadCodeRemover.run(code);
    assert code.isConsistentSSA();

    if (options.enableDesugaring && enableTryWithResourcesDesugaring()) {
      codeRewriter.rewriteThrowableAddAndGetSuppressed(code);
    }
    if (java8MethodRewriter != null) {
      java8MethodRewriter.desugar(code);
    }

    stringConcatRewriter.desugarStringConcats(method.method, code);

    if (lambdaRewriter != null) {
      lambdaRewriter.desugarLambdas(method, code);
      assert code.isConsistentSSA();
    }

    previous = printMethod(code, "IR after lambda desugaring (SSA)", previous);

    assert code.verifyTypes(appInfo, appView, graphLense());

    previous = printMethod(code, "IR before class inlining (SSA)", previous);

    if (classInliner != null) {
      // Class inliner should work before lambda merger, so if it inlines the
      // lambda, it does not get collected by merger.
      assert options.enableInlining && inliner != null;
      classInliner.processMethodCode(
          appInfo.withLiveness(),
          codeRewriter,
          stringOptimizer,
          method,
          code,
          isProcessedConcurrently,
          inliner,
          Suppliers.memoize(
              () ->
                  inliner.createDefaultOracle(
                      method,
                      code,
                      isProcessedConcurrently,
                      callSiteInformation,
                      Integer.MAX_VALUE / 2,
                      Integer.MAX_VALUE / 2)));
      assert code.isConsistentSSA();
    }

    previous = printMethod(code, "IR after class inlining (SSA)", previous);

    if (interfaceMethodRewriter != null) {
      interfaceMethodRewriter.rewriteMethodReferences(method, code);
      assert code.isConsistentSSA();
    }

    previous = printMethod(code, "IR after interface method rewriting (SSA)", previous);

    if (twrCloseResourceRewriter != null) {
      twrCloseResourceRewriter.rewriteMethodCode(code);
    }

    previous = printMethod(code, "IR after twr close resource rewriter (SSA)", previous);

    if (lambdaMerger != null) {
      lambdaMerger.processMethodCode(method, code);
      assert code.isConsistentSSA();
    }

    previous = printMethod(code, "IR after lambda merger (SSA)", previous);

    if (options.outline.enabled) {
      outlineHandler.accept(code, method);
      assert code.isConsistentSSA();
    }

    previous = printMethod(code, "IR after outline handler (SSA)", previous);

    // TODO(mkroghj) Test if shorten live ranges is worth it.
    if (!options.isGeneratingClassFiles()) {
      ConstantCanonicalizer.canonicalize(code);
      codeRewriter.useDedicatedConstantForLitInstruction(code);
      codeRewriter.shortenLiveRanges(code);
    }
    idempotentFunctionCallCanonicalizer.canonicalize(code);

    previous =
        printMethod(code, "IR after idempotent function call canonicalization (SSA)", previous);

    codeRewriter.identifyReturnsArgument(method, code, feedback);
    if (options.enableInlining && inliner != null) {
      codeRewriter.identifyInvokeSemanticsForInlining(method, code, appView, feedback);
    }

    // Track usage of parameters and compute their nullability and possibility of NPE.
    if (enableWholeProgramOptimizations) {
      if (method.getOptimizationInfo().getNonNullParamOrThrow() == null) {
        computeNonNullParamHints(feedback, method, code);
      }
    }

    // Insert code to log arguments if requested.
    if (options.methodMatchesLogArgumentsFilter(method)) {
      codeRewriter.logArgumentTypes(method, code);
      assert code.isConsistentSSA();
    }

    previous = printMethod(code, "IR after argument type logging (SSA)", previous);

    // Analysis must be done after method is rewritten by logArgumentTypes()
    codeRewriter.identifyClassInlinerEligibility(method, code, feedback);

    previous = printMethod(code, "IR after class inliner eligibility (SSA)", previous);

    if (method.isInstanceInitializer() || method.isClassInitializer()) {
      codeRewriter.identifyTrivialInitializer(method, code, feedback);
    }
    codeRewriter.identifyParameterUsages(method, code, feedback);
    if (classStaticizer != null) {
      classStaticizer.examineMethodCode(method, code);
    }

    if (options.canHaveNumberConversionRegisterAllocationBug()) {
      codeRewriter.workaroundNumberConversionRegisterAllocationBug(code);
    }

    // Either marked by IdentifierNameStringMarker or name reflection, or propagated from inlinee,
    // Then, make it visible to IdentifierMinifier.
    // Note that we place this at the end of IR processing because inlinee can be inlined by
    // Inliner, ClassInliner, or future optimizations that use the inlining machinery.
    if (method.getOptimizationInfo().useIdentifierNameString()) {
      feedback.markUseIdentifierNameString(method);
    } else {
      assert Streams.stream(code.instructionIterator())
          .noneMatch(Instruction::isDexItemBasedConstString);
    }

    printMethod(code, "Optimized IR (SSA)", previous);
    finalizeIR(method, code, feedback);
  }

  private void computeNonNullParamHints(
    OptimizationFeedback feedback, DexEncodedMethod method, IRCode code) {
    List<Value> arguments = code.collectArguments();
    BitSet paramsCheckedForNull = new BitSet();
    for (int index = 0; index < arguments.size(); index++) {
      Value argument = arguments.get(index);
      // This handles cases where the parameter is checked via Kotlin Intrinsics:
      //
      //   kotlin.jvm.internal.Intrinsics.checkParameterIsNotNull(param, message)
      //
      // or its inlined version:
      //
      //   if (param != null) return;
      //   invoke-static throwParameterIsNullException(msg)
      //
      // or some other variants, e.g., throw null or NPE after the direct null check.
      if (argument.isUsed() && checksNullBeforeSideEffect(code, argument, appView)) {
        paramsCheckedForNull.set(index);
      }
    }
    if (paramsCheckedForNull.length() > 0) {
      // Check if collected information conforms to non-null parameter hints in Kotlin metadata.
      // These hints are on the original holder. To find the original holder, we first find the
      // original method signature (this could have changed as a result of, for example, class
      // merging). Then, we find the type that now corresponds to the the original holder.
      DexMethod originalSignature = graphLense().getOriginalMethodSignature(method.method);
      DexClass originalHolder = definitionFor(graphLense().lookupType(originalSignature.holder));
      if (originalHolder.hasKotlinInfo()) {
        KotlinInfo kotlinInfo = originalHolder.getKotlinInfo();
        if (kotlinInfo.hasNonNullParameterHints()) {
          BitSet hintFromMetadata =
              kotlinInfo.lookupNonNullParameterHint(
                  originalSignature.name.toString(), originalSignature.proto.toDescriptorString());
          if (hintFromMetadata != null && hintFromMetadata.length() > 0) {
            if (!paramsCheckedForNull.equals(hintFromMetadata) && Log.ENABLED) {
              Log.debug(getClass(), "Mismatching non-null param hints for %s: %s v.s. %s\n%s",
                  paramsCheckedForNull.toString(),
                  hintFromMetadata.toString(),
                  method.toSourceString(),
                  logCode(options, method));
            }
          }
        }
      }
      feedback.setNonNullParamOrThrow(method, paramsCheckedForNull);
    }
  }

  private void finalizeIR(DexEncodedMethod method, IRCode code, OptimizationFeedback feedback) {
    code.traceBlocks();
    if (options.isGeneratingClassFiles()) {
      finalizeToCf(method, code, feedback);
    } else {
      assert options.isGeneratingDex();
      finalizeToDex(method, code, feedback);
    }
  }

  private void finalizeEmptyThrowingCode(DexEncodedMethod method, OptimizationFeedback feedback) {
    assert options.isGeneratingClassFiles() || options.isGeneratingDex();
    Code emptyThrowingCode =
        options.isGeneratingClassFiles()
            ? method.buildEmptyThrowingCfCode()
            : method.buildEmptyThrowingDexCode();
    method.setCode(emptyThrowingCode);
    feedback.markProcessed(method, ConstraintWithTarget.ALWAYS);
  }

  private void finalizeToCf(DexEncodedMethod method, IRCode code, OptimizationFeedback feedback) {
    assert !method.getCode().isDexCode();
    CfBuilder builder = new CfBuilder(method, code, options.itemFactory);
    CfCode result = builder.build(codeRewriter, graphLense(), options, appInfo);
    method.setCode(result);
    markProcessed(method, code, feedback);
  }

  private void finalizeToDex(DexEncodedMethod method, IRCode code, OptimizationFeedback feedback) {
    // Workaround massive dex2oat memory use for self-recursive methods.
    CodeRewriter.disableDex2OatInliningForSelfRecursiveMethods(code, options, appInfo);
    // Perform register allocation.
    RegisterAllocator registerAllocator = performRegisterAllocation(code, method);
    method.setCode(code, registerAllocator, options);
    updateHighestSortingStrings(method);
    if (Log.ENABLED) {
      Log.debug(getClass(), "Resulting dex code for %s:\n%s",
          method.toSourceString(), logCode(options, method));
    }
    printMethod(code, "Final IR (non-SSA)", null);
    markProcessed(method, code, feedback);
  }

  private void markProcessed(DexEncodedMethod method, IRCode code, OptimizationFeedback feedback) {
    // After all the optimizations have take place, we compute whether method should be inlined.
    ConstraintWithTarget state;
    if (!options.enableInlining
        || inliner == null
        || method.getOptimizationInfo().isReachabilitySensitive()) {
      state = ConstraintWithTarget.NEVER;
    } else {
      state = inliner.computeInliningConstraint(code, method);
    }
    feedback.markProcessed(method, state);
  }

  private synchronized void updateHighestSortingStrings(DexEncodedMethod method) {
    DexString highestSortingReferencedString = method.getCode().asDexCode().highestSortingString;
    if (highestSortingReferencedString != null) {
      if (highestSortingString == null
          || highestSortingReferencedString.slowCompareTo(highestSortingString) > 0) {
        highestSortingString = highestSortingReferencedString;
      }
    }
  }

  private RegisterAllocator performRegisterAllocation(IRCode code, DexEncodedMethod method) {
    // Always perform dead code elimination before register allocation. The register allocator
    // does not allow dead code (to make sure that we do not waste registers for unneeded values).
    deadCodeRemover.run(code);
    materializeInstructionBeforeLongOperationsWorkaround(code);
    workaroundForwardingInitializerBug(code);
    LinearScanRegisterAllocator registerAllocator =
        new LinearScanRegisterAllocator(appInfo, code, options);
    registerAllocator.allocateRegisters();
    if (options.canHaveExceptionTargetingLoopHeaderBug()) {
      codeRewriter.workaroundExceptionTargetingLoopHeaderBug(code);
    }
    printMethod(code, "After register allocation (non-SSA)", null);
    for (int i = 0; i < PEEPHOLE_OPTIMIZATION_PASSES; i++) {
      CodeRewriter.collapseTrivialGotos(method, code);
      PeepholeOptimizer.optimize(code, registerAllocator);
    }
    CodeRewriter.removeUnneededMovesOnExitingPaths(code, registerAllocator);
    CodeRewriter.collapseTrivialGotos(method, code);
    if (Log.ENABLED) {
      Log.debug(getClass(), "Final (non-SSA) flow graph for %s:\n%s",
          method.toSourceString(), code);
    }
    return registerAllocator;
  }

  private void workaroundForwardingInitializerBug(IRCode code) {
    if (!options.canHaveForwardingInitInliningBug()) {
      return;
    }
    // Only constructors.
    if (!code.method.isInstanceInitializer()) {
      return;
    }
    // Only constructors with certain signatures.
    DexTypeList paramTypes = code.method.method.proto.parameters;
    if (paramTypes.size() != 3 ||
        paramTypes.values[0] != options.itemFactory.doubleType ||
        paramTypes.values[1] != options.itemFactory.doubleType ||
        !paramTypes.values[2].isClassType()) {
      return;
    }
    // Only if the constructor contains a super constructor call taking only parameters as
    // inputs.
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      Instruction superConstructorCall = it.nextUntil((i) ->
          i.isInvokeDirect() &&
          i.asInvokeDirect().getInvokedMethod().name == options.itemFactory.constructorMethodName &&
          i.asInvokeDirect().arguments().size() == 4 &&
          i.asInvokeDirect().arguments().stream().allMatch(Value::isArgument));
      if (superConstructorCall != null) {
        // We force a materializing const instruction in front of the super call to make
        // sure that there is at least one temporary register in the method. That disables
        // the inlining that is crashing on these devices.
        ensureInstructionBefore(code, superConstructorCall, it);
        break;
      }
    }
  }

  /**
   * For each block, we look to see if the header matches:
   *
   * <pre>
   *   pseudo-instructions*
   *   v2 <- long-{mul,div} v0 v1
   *   pseudo-instructions*
   *   v5 <- long-{add,sub} v3 v4
   * </pre>
   *
   * where v2 ~=~ v3 or v2 ~=~ v4 (with ~=~ being equal or an alias of) and the block is not a
   * fallthrough target.
   */
  private void materializeInstructionBeforeLongOperationsWorkaround(IRCode code) {
    if (!options.canHaveDex2OatLinkedListBug()) {
      return;
    }
    DexItemFactory factory = options.itemFactory;
    final Supplier<DexMethod> javaLangLangSignum =
        Suppliers.memoize(
            () ->
                factory.createMethod(
                    factory.createString("Ljava/lang/Long;"),
                    factory.createString("signum"),
                    factory.intDescriptor,
                    new DexString[] {factory.longDescriptor}));
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator();
      Instruction firstMaterializing = it.nextUntil(IRConverter::isNotPseudoInstruction);
      if (!isLongMul(firstMaterializing)) {
        continue;
      }
      Instruction secondMaterializing = it.nextUntil(IRConverter::isNotPseudoInstruction);
      if (!isLongAddOrSub(secondMaterializing)) {
        continue;
      }
      if (isFallthoughTarget(block)) {
        continue;
      }
      Value outOfMul = firstMaterializing.outValue();
      for (Value inOfAddOrSub : secondMaterializing.inValues()) {
        if (isAliasOf(inOfAddOrSub, outOfMul)) {
          it = block.listIterator();
          it.nextUntil(i -> i == firstMaterializing);
          Value longValue = firstMaterializing.inValues().get(0);
          InvokeStatic invokeLongSignum =
              new InvokeStatic(
                  javaLangLangSignum.get(), null, Collections.singletonList(longValue));
          ensureThrowingInstructionBefore(code, firstMaterializing, it, invokeLongSignum);
          return;
        }
      }
    }
  }

  private static boolean isAliasOf(Value usedValue, Value definingValue) {
    while (true) {
      if (usedValue == definingValue) {
        return true;
      }
      Instruction definition = usedValue.definition;
      if (definition == null || !definition.isMove()) {
        return false;
      }
      usedValue = definition.asMove().src();
    }
  }

  private static boolean isNotPseudoInstruction(Instruction instruction) {
    return !(instruction.isDebugInstruction() || instruction.isMove());
  }

  private static boolean isLongMul(Instruction instruction) {
    return instruction != null
        && instruction.isMul()
        && instruction.asBinop().getNumericType() == NumericType.LONG
        && instruction.outValue() != null;
  }

  private static boolean isLongAddOrSub(Instruction instruction) {
    return instruction != null
        && (instruction.isAdd() || instruction.isSub())
        && instruction.asBinop().getNumericType() == NumericType.LONG;
  }

  private static boolean isFallthoughTarget(BasicBlock block) {
    for (BasicBlock pred : block.getPredecessors()) {
      if (pred.exit().fallthroughBlock() == block) {
        return true;
      }
    }
    return false;
  }

  private void ensureThrowingInstructionBefore(
      IRCode code, Instruction addBefore, InstructionListIterator it, Instruction instruction) {
    Instruction check = it.previous();
    assert addBefore == check;
    BasicBlock block = check.getBlock();
    if (block.hasCatchHandlers()) {
      // Split so the existing instructions retain their handlers and the new instruction has none.
      BasicBlock split = it.split(code);
      assert split.hasCatchHandlers();
      assert !block.hasCatchHandlers();
      it = block.listIterator(block.getInstructions().size() - 1);
    }
    instruction.setPosition(addBefore.getPosition());
    it.add(instruction);
  }

  private static void ensureInstructionBefore(
      IRCode code, Instruction addBefore, InstructionListIterator it) {
    // Force materialize a constant-zero before the long operation.
    Instruction check = it.previous();
    assert addBefore == check;
    // Forced definition of const-zero
    Value fixitValue = code.createValue(TypeLatticeElement.INT);
    Instruction fixitDefinition = new AlwaysMaterializingDefinition(fixitValue);
    fixitDefinition.setBlock(addBefore.getBlock());
    fixitDefinition.setPosition(addBefore.getPosition());
    it.add(fixitDefinition);
    // Forced user of the forced definition to ensure it has a user and thus live range.
    Instruction fixitUser = new AlwaysMaterializingUser(fixitValue);
    fixitUser.setBlock(addBefore.getBlock());
    fixitUser.setPosition(addBefore.getPosition());
    it.add(fixitUser);
  }

  private void printC1VisualizerHeader(DexEncodedMethod method) {
    if (printer != null) {
      printer.begin("compilation");
      printer.print("name \"").append(method.toSourceString()).append("\"").ln();
      printer.print("method \"").append(method.toSourceString()).append("\"").ln();
      printer.print("date 0").ln();
      printer.end("compilation");
    }
  }

  private void printPhase(String phase) {
    if (!options.extensiveLoggingFilter.isEmpty()) {
      System.out.println("Entering phase: " + phase);
    }
  }

  private String printMethod(IRCode code, String title, String previous) {
    if (printer != null) {
      printer.resetUnusedValue();
      printer.begin("cfg");
      printer.print("name \"").append(title).append("\"\n");
      code.print(printer);
      printer.end("cfg");
    }
    if (options.extensiveLoggingFilter.contains(code.method.method.toSourceString())) {
      String current = code.toString();
      System.out.println();
      System.out.println("-----------------------------------------------------------------------");
      System.out.println(title);
      System.out.println("-----------------------------------------------------------------------");
      if (previous != null && previous.equals(current)) {
        System.out.println("Unchanged");
      } else {
        System.out.println(current);
      }
      System.out.println("-----------------------------------------------------------------------");
      return current;
    }
    return previous;
  }
}
