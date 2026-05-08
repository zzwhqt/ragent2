import { Brain, Loader2 } from "lucide-react";

interface ThinkingIndicatorProps {
  content?: string;
  duration?: number;
}

export function ThinkingIndicator({ content, duration }: ThinkingIndicatorProps) {
  return (
    <div className="rounded-lg border border-[#FFD4B3] bg-[#FFF0E0] p-4">
      <div className="flex items-center gap-2 text-[#FF6A00]">
        <Loader2 className="h-4 w-4 animate-spin" />
        <span className="text-sm font-medium">正在深度思考...</span>
        {duration ? (
          <span className="text-xs text-[#FF6A00] bg-[#FFD4B3] px-2 py-0.5 rounded-full">
            {duration}秒
          </span>
        ) : null}
      </div>
      <div className="mt-3 flex items-start gap-2 text-sm text-[#B34400]">
        <Brain className="mt-0.5 h-4 w-4 shrink-0 text-[#FF6A00]" />
        <p className="whitespace-pre-wrap leading-relaxed">
          {content || ""}
          <span className="ml-1 inline-block h-4 w-1.5 animate-pulse bg-[#FF6A00] align-middle" />
        </p>
      </div>
    </div>
  );
}
