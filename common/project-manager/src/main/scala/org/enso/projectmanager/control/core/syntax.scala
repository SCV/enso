package org.enso.projectmanager.control.core

object syntax {

  implicit def toCovariantFlatMapOps[F[+_, +_]: CovariantFlatMap, E, A](
    fa: F[E, A]
  ): CovariantFlatMapOps[F, E, A] = new CovariantFlatMapOps[F, E, A](fa)

}
