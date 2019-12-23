package object utils {
  @specialized def ignore[A](evaluateForSideEffectOnly: A): Unit = {
    val _ = evaluateForSideEffectOnly
    () //Return unit to prevent wartremover warning due to discarding value
  }
}
