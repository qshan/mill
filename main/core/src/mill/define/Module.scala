package mill.define

import java.lang.reflect.Modifier

import mill.define.ParseArgs

import scala.language.experimental.macros
import scala.reflect.ClassTag
import scala.reflect.NameTransformer.decode

/**
 * `Module` is a class meant to be extended by `trait`s *only*, in order to
 * propagate the implicit parameters forward to the final concrete
 * instantiation site so they can capture the enclosing/line information of
 * the concrete instance.
 */
class Module(implicit outerCtx0: mill.define.Ctx) extends mill.moduledefs.Cacher { outer =>

  /**
   * Miscellaneous machinery around traversing & querying the build hierarchy,
   * that should not be needed by normal users of Mill
   */
  object millInternal extends Module.Internal(this)

  def millModuleDirectChildren: Seq[Module] = millModuleDirectChildrenImpl
  // We keep a private `lazy val` and a public `def` so
  // subclasses can call `super.millModuleDirectChildren`
  private lazy val millModuleDirectChildrenImpl: Seq[Module] =
    millInternal.reflectNestedObjects[Module].toSeq
  def millOuterCtx: Ctx = outerCtx0
  def millSourcePath: os.Path = millOuterCtx.millSourcePath / (millOuterCtx.segment match {
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(_) => Seq.empty[String] // drop cross segments
  })
  implicit def millModuleExternal: Ctx.External = Ctx.External(millOuterCtx.external)
  implicit def millModuleShared: Ctx.Foreign = Ctx.Foreign(millOuterCtx.foreign)
  implicit def millModuleBasePath: Ctx.BasePath = Ctx.BasePath(millSourcePath)
  implicit def millModuleSegments: Segments = {
    millOuterCtx.segments ++ Seq(millOuterCtx.segment)
  }
  override def toString = millModuleSegments.render
}

object Module {
  class Internal(outer: Module) {
    def traverse[T](f: Module => Seq[T]): Seq[T] = {
      def rec(m: Module): Seq[T] = f(m) ++ m.millModuleDirectChildren.flatMap(rec)
      rec(outer)
    }

    lazy val modules: Seq[Module] = traverse(Seq(_))
    lazy val segmentsToModules = modules.map(m => (m.millModuleSegments, m)).toMap

    lazy val targets: Set[Target[_]] =
      traverse { _.millInternal.reflectAll[Target[_]].toIndexedSeq }.toSet

    lazy val segmentsToTargets: Map[Segments, Target[_]] = targets
      .map(t => (t.ctx.segments, t))
      .toMap

    // Ensure we do not propagate the implicit parameters as implicits within
    // the body of any inheriting class/trait/objects, as it would screw up any
    // one else trying to use sourcecode.{Enclosing,Line} to capture debug info
    lazy val millModuleEnclosing: String = outer.millOuterCtx.enclosing
    lazy val millModuleLine: Int = outer.millOuterCtx.lineNum

    private def reflect[T: ClassTag](filter: (String) => Boolean): Array[T] = {
      val runtimeCls = implicitly[ClassTag[T]].runtimeClass
      for {
        m <- outer.getClass.getMethods.sortBy(_.getName)
        n = decode(m.getName)
        if filter(n) &&
          ParseArgs.isLegalIdentifier(n) &&
          m.getParameterCount == 0 &&
          (m.getModifiers & Modifier.STATIC) == 0 &&
          (m.getModifiers & Modifier.ABSTRACT) == 0 &&
          runtimeCls.isAssignableFrom(m.getReturnType)
      } yield m.invoke(outer).asInstanceOf[T]
    }

    def reflectAll[T: ClassTag]: Array[T] = reflect(Function.const(true))

    def reflectSingle[T: ClassTag](label: String): Option[T] = reflect(_ == label).headOption

    // For some reason, this fails to pick up concrete `object`s nested directly within
    // another top-level concrete `object`. This is fine for now, since Mill's Ammonite
    // script/REPL runner always wraps user code in a wrapper object/trait
    def reflectNestedObjects[T: ClassTag]: Array[T] = {
      (reflectAll[T] ++
        outer
          .getClass
          .getClasses
          .filter(implicitly[ClassTag[T]].runtimeClass.isAssignableFrom(_))
          .flatMap(c =>
            c.getFields.find(_.getName == "MODULE$").map(_.get(c).asInstanceOf[T])
          )).distinct
    }
  }
}
trait TaskModule extends Module {
  def defaultCommandName(): String
}
