// Copyright 2017 MPI-SWS, Saarbruecken, Germany

package daisy
package tools

import lang.Trees._
import lang.Types._
import lang.Identifiers._
import FinitePrecision._
import Rational._
import daisy.utils.CachingMap

trait RoundoffEvaluators extends RangeEvaluators {

  /**
   * Calculates the roundoff error for a given uniform precision
   * using interval arithmetic for ranges and affine arithmetic for errors.
   *
   * @param expr expression for which to compute roundoff
   * @param inputValMap real-valued ranges of all input variables
   * @param inputErrorMap errors of all input variables (incl. roundoff)
   * @param uniformPrecision precision for the entire computation
   *
   * @return (max. absolute roundoff error bound, real-valued result interval)
   */
  def uniformRoundoff_IA_IA(
    expr: Expr,
    inputValMap: Map[Identifier, Interval],
    inputErrorMap: Map[Identifier, Rational],
    uniformPrecision: Precision,
    trackRoundoffErrors: Boolean = true,
    approxRoundoff: Boolean = false): (Rational, Interval) = {

    val (resRange, intermediateRanges) = evalRange[Interval](expr, inputValMap, Interval.apply)

    val (resRoundoff, _) = evalRoundoff[Interval](expr, intermediateRanges,
      Map.empty.withDefaultValue(uniformPrecision),
      inputErrorMap.mapValues(Interval.+/-),
      zeroError = Interval.zero,
      fromError = Interval.+/-,
      interval2T = Interval.apply,
      constantsPrecision = uniformPrecision,
      trackRoundoffErrors,
      approxRoundoff)

    (Interval.maxAbs(resRoundoff.toInterval), resRange)
  }

  /**
   * Calculates the roundoff error for a given uniform precision
   * using interval arithmetic for ranges and affine arithmetic for errors.
   *
   * @param expr expression for which to compute roundoff
   * @param inputValMap real-valued ranges of all input variables
   * @param inputErrorMap errors of all input variables (incl. roundoff)
   * @param uniformPrecision precision for the entire computation
   *
   * @return (max. absolute roundoff error bound, real-valued result interval)
   */
  def uniformRoundoff_IA_AA(
    expr: Expr,
    inputValMap: Map[Identifier, Interval],
    inputErrorMap: Map[Identifier, Rational],
    uniformPrecision: Precision,
    trackRoundoffErrors: Boolean = true,
    approxRoundoff: Boolean = false): (Rational, Interval) = {

    val (resRange, intermediateRanges) = evalRange[Interval](expr, inputValMap, Interval.apply)

    val (resRoundoff, _) = evalRoundoff[AffineForm](expr, intermediateRanges,
      Map.empty.withDefaultValue(uniformPrecision),
      inputErrorMap.mapValues(AffineForm.+/-),
      zeroError = AffineForm.zero,
      fromError = AffineForm.+/-,
      interval2T = AffineForm.apply,
      constantsPrecision = uniformPrecision,
      trackRoundoffErrors,
      approxRoundoff)

    (Interval.maxAbs(resRoundoff.toInterval), resRange)
  }

  /**
   * Calculates the roundoff error for a given uniform precision
   * using affine arithmetic for ranges and affine arithmetic for errors.
   *
   * @param expr expression for which to compute roundoff
   * @param inputValMap real-valued ranges of all input variables
   * @param inputErrorMap errors of all input variables (incl. roundoff)
   * @param uniformPrecision precision for the entire computation
   */
  def uniformRoundoff_AA_AA(
    expr: Expr,
    inputValMap: Map[Identifier, Interval],
    inputErrorMap: Map[Identifier, Rational],
    uniformPrecision: Precision,
    trackRoundoffErrors: Boolean = true,
    approxRoundoff: Boolean = false): (Rational, Interval) = {

    val (resRange, intermediateRanges) = evalRange[AffineForm](expr,
      inputValMap.mapValues(AffineForm(_)), AffineForm.apply)

    val (resRoundoff, _) = evalRoundoff[AffineForm](expr,
      intermediateRanges.mapValues(_.toInterval),
      Map.empty.withDefaultValue(uniformPrecision),
      inputErrorMap.mapValues(AffineForm.+/-),
      zeroError = AffineForm.zero,
      fromError = AffineForm.+/-,
      interval2T = AffineForm.apply,
      constantsPrecision = uniformPrecision,
      trackRoundoffErrors,
      approxRoundoff)

    (Interval.maxAbs(resRoundoff.toInterval), resRange.toInterval)
  }

  /**
   * Calculates the roundoff error for a given uniform precision
   * using SMTRange for ranges and affine arithmetic for errors.
   *
   * @param expr expression for which to compute roundoff
   * @param inputValMap real-valued ranges of all input variables
   * @param inputErrorMap errors of all input variables (incl. roundoff)
   * @param uniformPrecision precision for the entire computation
   */
  def uniformRoundoff_SMT_AA(
    expr: Expr,
    inputValMap: Map[Identifier, Interval],
    inputErrorMap: Map[Identifier, Rational],
    precondition: Expr,
    uniformPrecision: Precision,
    trackRoundoffErrors: Boolean = true,
    approxRoundoff: Boolean = false): (Rational, Interval) = {

    val (resRange, intermediateRanges) = evalRange[SMTRange](expr,
      inputValMap.map({ case (id, int) => (id -> SMTRange(Variable(id), int, precondition)) }),
      SMTRange.apply(_, precondition))

    val (resRoundoff, _) = evalRoundoff[AffineForm](expr,
      intermediateRanges.mapValues(_.toInterval),
      Map.empty.withDefaultValue(uniformPrecision),
      inputErrorMap.mapValues(AffineForm.+/-),
      zeroError = AffineForm.zero,
      fromError = AffineForm.+/-,
      interval2T = AffineForm.apply,
      constantsPrecision = uniformPrecision,
      trackRoundoffErrors,
      approxRoundoff)

    (Interval.maxAbs(resRoundoff.toInterval), resRange.toInterval)
  }

  /**
   * Theorem statement: If y / 2 <= x <= 2 * y
   * then the result of (x - y) does not produce any roundoff error
   * @param x
   * @param y
   * @return true if the theorem applies, false otherwise
   */
  @inline
  private def sterbenzTheoremApplies(x: Interval, y: Interval): Boolean = {
    x.xhi <= 2 * y.xlo && y.xhi <= 2 * x.xlo
  }

  /**
    Computes the absolute roundoff error for the given expression.

    The ranges of all the intermediate expressions have to be given in rangeMap.
    Allows mixed-precision by providing (possibly different) precisions for
    all declared variables (input parameters as well as locally defined variables.)
    Constants are assumed to be all in one precision, given by the user.

   */
  def evalRoundoff[T <: RangeArithmetic[T]](
    expr: Expr,
    range: Map[Expr, Interval],
    precision: Map[Identifier, Precision],
    freeVarsError: Map[Identifier, T],
    zeroError: T,
    fromError: Rational => T,
    interval2T: Interval => T,
    constantsPrecision: Precision,
    trackRoundoffErrors: Boolean, // if false, propagate only initial errors
    approxRoundoff: Boolean = false
    ): (T, Map[Expr, T]) = {


    val intermediateErrors = new CachingMap[Expr, (T, Precision)]

    for ((id, err) <- freeVarsError){
      intermediateErrors.put(Variable(id), (err, precision(id)))
    }

    def computeNewError(range: Interval, propagatedError: T, prec: Precision): (T, Precision) = _computeNewError(range, propagatedError, prec, prec.absRoundoff)

    def computeNewErrorTranscendental(range: Interval, propagatedError: T, prec: Precision): (T, Precision) = _computeNewError(range, propagatedError, prec, prec.absTranscendentalRoundoff)

    def _computeNewError(range: Interval, propagatedError: T, prec: Precision,
                         roundoffComputationMethod: Interval => Rational): (T, Precision) =
    if (trackRoundoffErrors) {
      val actualRange: Interval = range + propagatedError.toInterval
      var rndoff = roundoffComputationMethod(actualRange)
      if (approxRoundoff) {
        rndoff = Rational.limitSize(rndoff)
      }
      (propagatedError +/- rndoff, prec)
    } else {
      (propagatedError, prec)
    }

    def eval(e: Expr): (T, Precision) = intermediateErrors.getOrAdd(e, {

      case x @ RealLiteral(r) =>
        val error =
          //if (constantsPrecision.canRepresent(r) || !trackRoundoffErrors) {
          //zeroError
        //} else {
          fromError(constantsPrecision.absRoundoff(r))
        //}
        (error, constantsPrecision)

      case x @ Plus(lhs, rhs) =>
        val (errorLhs, precLhs) = eval(lhs)
        val (errorRhs, precRhs) = eval(rhs)

        val rangeLhs = range(lhs)
        val rangeRhs = range(rhs)

        val errIVLhs = rangeLhs +/- (Interval.maxAbs (errorLhs.toInterval))
        val errIVRhs = rangeRhs +/- (Interval.maxAbs (errorRhs.toInterval))
        val actualRange = errIVLhs + errIVRhs

        val propagatedError = errorLhs + errorRhs

        val precision = getUpperBound(precLhs, precRhs)

        val rndoff = precision.absRoundoff(actualRange)

        (propagatedError +/- rndoff, precision)

      case x @ Minus(lhs, rhs) =>
        val (errorLhs, precLhs) = eval(lhs)
        val (errorRhs, precRhs) = eval(rhs)

        val rangeLhs = range(lhs)
        val rangeRhs = range(rhs)

        val errIVLhs = rangeLhs +/- (Interval.maxAbs (errorLhs.toInterval))
        val errIVRhs = rangeRhs +/- (Interval.maxAbs (errorRhs.toInterval))
        val actualRange = errIVLhs - errIVRhs

        val propagatedError = errorLhs - errorRhs

        val precision = getUpperBound(precLhs, precRhs)

        val rndoff = precision.absRoundoff(actualRange)

        (propagatedError +/- rndoff, precision)

      case x @ Times(lhs, rhs) =>
        val (errorLhs, precLhs) = eval(lhs)
        val (errorRhs, precRhs) = eval(rhs)

        val rangeLhs = range(lhs)
        val rangeRhs = range(rhs)

        val abstractRangeLhs = interval2T(rangeLhs)
        val abstractRangeRhs = interval2T(rangeRhs)

        val errIVLhs = rangeLhs +/- (Interval.maxAbs (errorLhs.toInterval))
        val errIVRhs = rangeRhs +/- (Interval.maxAbs (errorRhs.toInterval))
        val actualRange = errIVLhs * errIVRhs

        val propagatedError =
          abstractRangeLhs * errorRhs +
          abstractRangeRhs * errorLhs +
          errorLhs * errorRhs

        val precision = getUpperBound(precLhs, precRhs)

        val rndoff = precision.absRoundoff(actualRange)

        (propagatedError +/- rndoff, precision)
        // No roundoff error if one of the operands is a non-negative power of 2
        // if ((rangeLhs.isNonNegative && rangeLhs.isPowerOf2)
        //   || (rangeRhs.isNonNegative && rangeRhs.isPowerOf2)) {
        //   (propagatedError, precision)
        // } else {
          // computeNewError(range(x), propagatedError, precision)
        // }

      case x @ FMA(fac1, fac2, sum) =>
        val (errorFac1, precFac1) = eval(fac1)
        val (errorFac2, precFac2) = eval(fac2)
        val (errorSum, precSum) = eval(sum)

        val rangeFac1 = range(fac1)
        val rangeFac2 = range(fac2)
        val rangeSum = range(sum)

        val abstractRangeFac1 = interval2T(range(fac1))
        val abstractRangeFac2 = interval2T(range(fac2))
        // val abstractRangeSum = interval2T(range(sum))

        val errIVFac1 = rangeFac1 +/- Interval.maxAbs(errorFac1.toInterval)
        val errIVFac2 = rangeFac2 +/- Interval.maxAbs(errorFac2.toInterval)
        val errIVSum = rangeSum +/- Interval.maxAbs(errorSum.toInterval)
        val actualRange = errIVFac1 * errIVFac2 + errIVSum

        val propagatedError =
          abstractRangeFac1 * errorFac2 +
          abstractRangeFac2 * errorFac1 +
          errorFac1 * errorFac2 +
          errorSum

        val precision = getUpperBound(precFac1, precFac2, precSum)

        val rndoff = precision.absRoundoff(actualRange)

        (propagatedError +/- rndoff, precision)
        // computeNewError(range(x), propagatedError, getUpperBound(precFac1, precFac2, precSum))

      case x @ Division(lhs, rhs) =>
        val (errorLhs, precLhs) = eval(lhs)
        val (errorRhs, precRhs) = eval(rhs)

        val rangeLhs = range(lhs)
        val rangeRhs = range(rhs)

        // inverse, i.e. we are computing x * (1/y)
        val rightInterval = rangeRhs + errorRhs.toInterval // the actual interval, incl errors

        // the actual error interval can now contain 0, check this
        if (rightInterval.includes(Rational.zero)) {
          throw DivisionByZeroException("trying to divide by error interval containing 0")
        }
        val a = Interval.minAbs(rightInterval)
        val errorMultiplier: Rational = -one / (a*a)
        val invErr = errorRhs * errorMultiplier

        // error propagation
        val inverse: Interval = rangeRhs.inverse

        val errIVLhs = rangeLhs +/- (Interval.maxAbs (errorLhs.toInterval))
        val errIVRhs = inverse +/- (Interval.maxAbs (invErr.toInterval))
        val actualRange = errIVLhs * errIVRhs

        val propagatedError =
          interval2T(rangeLhs) * invErr +
          interval2T(inverse) * errorLhs +
          errorLhs * invErr

        val precision = getUpperBound(precLhs, precRhs)

        val rndoff = precision.absRoundoff(actualRange)

        (propagatedError +/- rndoff, precision)

      case x @ IntPow(base, n) =>
        val (errorT, prec) = eval(base)
        val rangeT = interval2T(range(base))

        var r = rangeT
        var e = errorT
        for (_ <- 0 until n) {
          e = r * errorT + rangeT * e + e * errorT
          r *= rangeT
        }
        // The error of pow in java.Math is 1 ulp, thus we rely that the method
        // computeNewErrorTranscendental gives us 1 ulp error
        computeNewErrorTranscendental(r.toInterval, e, prec)

      case x @ UMinus(t) =>
        val (error, prec) = eval(t)
        (- error, prec)

      case x @ Sqrt(t) =>
        // TODO: needs to fail for fixed-point precision
        val (errorT, prec) = eval(t)
        val rangeT = range(t)

        if ((errorT.toInterval.xlo + rangeT.xlo) <= Rational.zero && Sign.ofExpression(t, range) != Sign.Positive) {
          throw NegativeSqrtException("trying to take the square root of a negative number or zero")
        }

        val a = Interval.minAbs(rangeT)
        val errorMultiplier = Rational(1L, 2L) / sqrtDown(a)

        val propagatedError = errorT * errorMultiplier

        // TODO: check that this operation exists for this precision
        computeNewError(range(x), propagatedError, prec)

      case x @ Sin(t) =>
        // TODO not supported for fixed-points
        val (errorT, prec) = eval(t)

        // Bound the slope of sin(x) over the range by computing its
        // derivative (i.e. cos(x)) as an interval and then taking the bound
        // with the larger absolute value.
        val deriv =  range(t).cosine
        val errorMultiplier = if (abs(deriv.xlo) > abs(deriv.xhi)) deriv.xlo else deriv.xhi
        val propagatedError = errorT * errorMultiplier

        // TODO: check that this operation exists for this precision
        computeNewErrorTranscendental(range(x), propagatedError, prec)

      case x @ Cos(t) =>
        // TODO not supported for fixed-points
        val (errorT, prec) = eval(t)

        // Bound the slope of cos(x) over the range by computing its
        // derivative (i.e. -sin(x)) as an interval and then taking the bound
        // with the larger absolute value.
        val deriv = -range(t).sine
        val errorMultiplier = if (abs(deriv.xlo) > abs(deriv.xhi)) deriv.xlo else deriv.xhi
        val propagatedError = errorT * errorMultiplier

        // TODO: check that this operation exists for this precision
        computeNewErrorTranscendental(range(x), propagatedError, prec)

      case x @ Tan(t) =>
        // TODO not supported for fixed-points
        val (errorT, prec) = eval(t)

        // compute the derivative as 1/cos^2(x)
        val intCosine = range(t).cosine
        val deriv = (intCosine * intCosine).inverse

        val errorMultiplier = if (abs(deriv.xlo) > abs(deriv.xhi)) deriv.xlo else deriv.xhi
        val propagatedError = errorT * errorMultiplier

        // TODO: check that this operation exists for this precision
        computeNewErrorTranscendental(range(x), propagatedError, prec)

      case x @ Exp(t) =>
        // TODO not supported for fixed-points
        val (errorT, prec) = eval(t)

        // maximal slope is always at the right ending point
        val b = range(t).xhi

        // compute the maximal slope over the interval
        // (exp(x) is the derivative of exp(x))
        val errorMultiplier = expUp(b)

        val propagatedError = errorT * errorMultiplier

        // TODO: check that this operation exists for this precision
        computeNewErrorTranscendental(range(x), propagatedError, prec)

      case x @ Log(t) =>
        // TODO not supported for fixed-points
        val (errorT, prec) = eval(t)

        // maximal slope is always at the left ending point
        val a = range(t).xlo

        // compute the maximal slope over the interval (1/x is the derivative of log(x))
        val errorMultiplier = Rational.one / a

        val propagatedError = errorT * errorMultiplier

        // TODO: check that this operation exists for this precision
        computeNewErrorTranscendental(range(x), propagatedError, prec)

      case x @ Let(id, value, body) =>
        val (valueError, valuePrec) = eval(value)

        val idPrec = precision(id)
        val error = if (idPrec < valuePrec) { // we need to cast down
          val valueRange = range(value)
          val err = computeNewError(valueRange, valueError, idPrec)
          intermediateErrors.put(Cast(value,FinitePrecisionType(idPrec)),err)
          err
        } else {
          (valueError,valuePrec)
        }
        intermediateErrors.put(Variable(id), error) // no problem as identifiers are unique
        eval(body)

      case Variable(_) => throw new Exception("Unknown variable")

      case _ => throw new Exception("Not supported")

    })
    val (resError, _) = eval(expr)
    (resError, intermediateErrors.mapValues(_._1).toMap)
  }


}
