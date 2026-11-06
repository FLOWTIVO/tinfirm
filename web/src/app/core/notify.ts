import { Injectable } from '@angular/core';
import { toast } from '@spartan-ng/brain/sonner';
import { problemMessage } from '../api/problem';

export interface NotifyAction {
  readonly label: string;
  readonly run: () => void;
}

/** Toast notifications for the outcome of user actions. */
@Injectable({ providedIn: 'root' })
export class Notify {
  success(message: string, description?: string, action?: NotifyAction): void {
    toast.success(message, {
      ...(description ? { description } : {}),
      ...(action ? { action: { label: action.label, onClick: () => action.run() } } : {}),
    });
  }

  /** Something the user has to finish by hand, without a failed request to explain it. */
  warning(message: string, description: string): void {
    toast.warning(message, { description });
  }

  /**
   * Shows the problem-details message of a failed request, with an action when the person can fix
   * it elsewhere (connect an account under My accounts, ADR-0049 §4).
   */
  error(
    title: string,
    error: unknown,
    overrides?: Record<string, string>,
    action?: NotifyAction,
  ): void {
    toast.error(title, {
      description: problemMessage(error, overrides),
      ...(action ? { action: { label: action.label, onClick: () => action.run() } } : {}),
    });
  }
}
