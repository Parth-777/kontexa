import { useEffect, useRef, useState } from 'react';

export default function useInView(options = {}) {
  const ref = useRef(null);
  const [inView, setInView] = useState(false);
  const { threshold = 0.25, rootMargin = '0px 0px -10% 0px', once = true } = options;

  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;

    if (typeof window !== 'undefined'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setInView(true);
      return undefined;
    }

    const obs = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) {
        setInView(true);
        if (once) obs.disconnect();
      } else if (!once) {
        setInView(false);
      }
    }, { threshold, rootMargin });

    obs.observe(el);
    return () => obs.disconnect();
  }, [threshold, rootMargin, once]);

  return [ref, inView];
}
