package algebra
package laws

import algebra.ring._

import org.typelevel.discipline.Predicate

import org.scalacheck.{Arbitrary, Prop}
import org.scalacheck.Arbitrary._
import org.scalacheck.Prop._

object RingLaws {
  def apply[A : Eq : Arbitrary](implicit pred0: Predicate[A]) = new RingLaws[A] {
    def Arb = implicitly[Arbitrary[A]]
    def pred = pred0
    val nonZeroLaws = new GroupLaws[A] {
      def Arb = Arbitrary(arbitrary[A] filter pred0)
      def Equ = Eq[A]
    }
  }
}

trait RingLaws[A] extends GroupLaws[A] {

  // must be a val (stable identifier)
  val nonZeroLaws: GroupLaws[A]
  def pred: Predicate[A]

  def withPred(pred0: Predicate[A], replace: Boolean = true): RingLaws[A] = RingLaws[A](
    Equ,
    Arb,
    if (replace) pred0 else pred && pred0
  )

  implicit def Arb: Arbitrary[A]
  implicit def Equ: Eq[A] = nonZeroLaws.Equ

  // multiplicative groups

  def multiplicativeSemigroup(implicit A: MultiplicativeSemigroup[A]) = new MultiplicativeProperties(
    base = _.semigroup(A.multiplicative),
    parent = None,
    Rules.serializable(A),
    Rules.repeat1("pow")(A.pow),
    Rules.repeat2("pow", "*")(A.pow)(A.times)
  )

  def multiplicativeCommutativeSemigroup(implicit A: MultiplicativeCommutativeSemigroup[A]) = new MultiplicativeProperties(
    base = _.semigroup(A.multiplicative),
    parent = Some(multiplicativeSemigroup)
  )

  def multiplicativeMonoid(implicit A: MultiplicativeMonoid[A]) = new MultiplicativeProperties(
    base = _.monoid(A.multiplicative),
    parent = Some(multiplicativeSemigroup),
    Rules.repeat0("pow", "one", A.one)(A.pow),
    Rules.collect0("product", "one", A.one)(A.product)
  )

  def multiplicativeCommutativeMonoid(implicit A: MultiplicativeCommutativeMonoid[A]) = new MultiplicativeProperties(
    base = _.commutativeMonoid(A.multiplicative),
    parent = Some(multiplicativeMonoid)
  )

  def multiplicativeGroup(implicit A: MultiplicativeGroup[A]) = new MultiplicativeProperties(
    base = _.group(A.multiplicative),
    parent = Some(multiplicativeMonoid),
    // pred is used to ensure y is not zero.
    "consistent division" -> forAll { (x: A, y: A) =>
      pred(y) ==> (A.div(x, y) ?== A.times(x, A.reciprocal(y)))
    }
  )

  def multiplicativeCommutativeGroup(implicit A: MultiplicativeCommutativeGroup[A]) = new MultiplicativeProperties(
    base = _.commutativeGroup(A.multiplicative),
    parent = Some(multiplicativeGroup)
  )

  // rings

  def semiring(implicit A: Semiring[A]) = new RingProperties(
    name = "semiring",
    al = additiveCommutativeMonoid,
    ml = multiplicativeSemigroup,
    parents = Seq.empty,
    Rules.distributive(A.plus)(A.times)
  )

  def rng(implicit A: Rng[A]) = new RingProperties(
    name = "rng",
    al = additiveCommutativeGroup,
    ml = multiplicativeSemigroup,
    parents = Seq(semiring)
  )

  def rig(implicit A: Rig[A]) = new RingProperties(
    name = "rig",
    al = additiveCommutativeMonoid,
    ml = multiplicativeMonoid,
    parents = Seq(semiring)
  )

  def ring(implicit A: Ring[A]) = new RingProperties(
    // TODO fromParents
    name = "ring",
    al = additiveCommutativeGroup,
    ml = multiplicativeMonoid,
    parents = Seq(rig, rng),
    "fromInt" -> forAll { (n: Int) =>
      Ring.fromInt[A](n) ?== A.sumN(A.one, n)
    },
    "fromBigInt" -> forAll { (ns: List[Int]) =>
      val actual = Ring.fromBigInt[A](ns.map(BigInt(_)).foldLeft(BigInt(1))(_ * _))
      val expected = ns.map(A.fromInt).foldLeft(A.one)(A.times)
      actual ?== expected
    }
  )

  // commutative rings

  def commutativeSemiring(implicit A: CommutativeSemiring[A]) = new RingProperties(
    name = "commutativeSemiring",
    al = additiveCommutativeMonoid,
    ml = multiplicativeCommutativeSemigroup,
    parents = Seq(semiring)
  )

  def commutativeRng(implicit A: CommutativeRng[A]) = new RingProperties(
    name = "commutativeRng",
    al = additiveCommutativeMonoid,
    ml = multiplicativeCommutativeSemigroup,
    parents = Seq(rng, commutativeSemiring)
  )

  def commutativeRig(implicit A: CommutativeRig[A]) = new RingProperties(
    name = "commutativeRig",
    al = additiveCommutativeMonoid,
    ml = multiplicativeCommutativeMonoid,
    parents = Seq(rig, commutativeSemiring)
  )

  def commutativeRing(implicit A: CommutativeRing[A]) = new RingProperties(
    name = "commutative ring",
    al = additiveCommutativeGroup,
    ml = multiplicativeCommutativeMonoid,
    parents = Seq(ring, commutativeRig, commutativeRng)
  )

  // boolean rings

  def boolRng(implicit A: BoolRng[A]) = RingProperties.fromParent(
    name = "boolean rng",
    parent = commutativeRng,
    Rules.idempotence(A.times)
  )

  def boolRing(implicit A: BoolRing[A]) = RingProperties.fromParent(
    name = "boolean ring",
    parent = commutativeRing,
    Rules.idempotence(A.times)
  )

  def euclideanRing(implicit A: EuclideanRing[A]) = RingProperties.fromParent(
    name = "euclidean ring",
    parent = commutativeRing,
    "quotmod" -> forAll { (x: A, y: A) =>
      pred(y) ==> {
        val (q, r) = A.quotmod(x, y)
        A.plus(A.times(y, q), r) ?== x
      }
    },
    "quot" -> forAll { (x: A, y: A) =>
      pred(y) ==> (A.quot(x, y) ?== A.quotmod(x, y)._1)
    },
    "mod" -> forAll { (x: A, y: A) =>
      pred(y) ==> (A.mod(x, y) ?== A.quotmod(x, y)._2)
    }
  )

  // Everything below fields (e.g. rings) does not require their multiplication
  // operation to be a group. Hence, we do not check for the existence of an
  // inverse. On the other hand, fields require their multiplication to be an
  // abelian group. Now we have to worry about zero.
  //
  // The usual text book definition says: Fields consist of two abelian groups
  // (set, +, zero) and (set \ zero, *, one). We do the same thing here.
  // However, since law checking for the multiplication does not include zero
  // any more, it is not immediately clear that desired properties like
  // zero * x == x * zero hold.
  // Luckily, these follow from the other field and group axioms.
  def field(implicit A: Field[A]) = new RingProperties(
    name = "field",
    al = additiveCommutativeGroup,
    ml = multiplicativeCommutativeGroup,
    parents = Seq(euclideanRing)
  ) {
    override def nonZero = true
  }


  // property classes

  class MultiplicativeProperties(
    val base: GroupLaws[A] => GroupLaws[A]#GroupProperties,
    val parent: Option[MultiplicativeProperties],
    val props: (String, Prop)*
  ) extends RuleSet with HasOneParent {
    private val base0 = base(RingLaws.this)

    val name = base0.name
    val bases = Seq("base" -> base0)
  }

  object RingProperties {
    def fromParent(name: String, parent: RingProperties, props: (String, Prop)*) =
      new RingProperties(name, parent.al, parent.ml, Seq(parent), props: _*)
  }

  class RingProperties(
    val name: String,
    val al: AdditiveProperties,
    val ml: MultiplicativeProperties,
    val parents: Seq[RingProperties],
    val props: (String, Prop)*
  ) extends RuleSet {
    def nonZero: Boolean = false

    def ml0 = if (!nonZero) ml else {
      new RuleSet with HasOneParent {
        val name = ml.name
        val bases = Seq("base-nonzero" -> ml.base(nonZeroLaws))
        val parent = ml.parent
        val props = ml.props
      }
    }

    def bases = Seq("additive" -> al, "multiplicative" -> ml0)
  }
}
