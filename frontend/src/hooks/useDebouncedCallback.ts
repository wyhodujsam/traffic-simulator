import { useRef, useCallback } from 'react';

/**
 * Returns a debounced version of the callback.
 * Calls are delayed by `delay` ms; rapid calls reset the timer.
 */
export function useDebouncedCallback<T extends (...args: unknown[]) => void>(
  callback: T,
  delay: number
): T {
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  return useCallback(
    ((...args: unknown[]) => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
      }
      timerRef.current = setTimeout(() => {
        callback(...args);
        timerRef.current = null;
      }, delay);
    }) as T,
    [callback, delay]
  );
}
