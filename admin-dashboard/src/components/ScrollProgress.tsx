import { motion, useScroll, useSpring } from "framer-motion";

/** Thin gradient bar pinned to the top, fills as you scroll. */
export function ScrollProgress() {
  const { scrollYProgress } = useScroll();
  const x = useSpring(scrollYProgress, { stiffness: 120, damping: 20 });
  return <motion.div className="scroll-progress" style={{ scaleX: x }}/>;
}
