import { useEffect, useState } from "react"

const MOBILE_BREAKPOINT = 1024
const MEDIUM_BREAKPOINT = 768
const SMALL_BREAKPOINT = 640

// Lazy initial state: reading innerWidth on every render forced a style and
// layout flush in the middle of rendering (list and sidebar re-render often).
const mobileAt = (width) => width <= SMALL_BREAKPOINT || (width >= MEDIUM_BREAKPOINT && width <= MOBILE_BREAKPOINT)

export function useIsMobile() {
  const [isMobile, setIsMobile] = useState(() => mobileAt(window.innerWidth))
  const [isMedium, setIsMedium] = useState(() => window.innerWidth <= MEDIUM_BREAKPOINT)
  const [isSmall, setIsSmall] = useState(() => window.innerWidth <= SMALL_BREAKPOINT)

  useEffect(() => {
    const handleResize = () => {
      const width = window.innerWidth
      setIsMobile(mobileAt(width))
      setIsMedium(width <= MEDIUM_BREAKPOINT)
      setIsSmall(width <= SMALL_BREAKPOINT)
    }

    window.addEventListener("resize", handleResize)
    return () => window.removeEventListener("resize", handleResize)
  }, [])

  return { isMobile, isMedium, isSmall }
}
