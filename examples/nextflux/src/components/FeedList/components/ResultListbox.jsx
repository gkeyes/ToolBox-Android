import { Description, Label, ListBox, Separator } from "@heroui/react";
import { cn } from "@/lib/utils";
import FeedIcon from "@/components/ui/FeedIcon";
import { Image } from "@/components/ui/Image.jsx";
export default function ResultListbox({ results, searchType, handleSelect }) {
  const ListboxWrapper = ({ children }) => (
    <div
      className={cn(
        "w-full h-auto overflow-y-auto p-1 rounded-[var(--ds-radius-card)] bg-[color-mix(in_srgb,var(--ds-on-surface)_4%,var(--ds-raised))] max-h-60 overscroll-contain",
        results.length === 0 ? "hidden" : "opacity-100",
      )}
    >
      {children}
    </div>
  );
  return (
    <>
      <Separator
        className={cn("my-1 opacity-0", results.length === 0 ? "hidden" : "")}
      />
      <ListboxWrapper>
        <ListBox
          aria-label="results"
          selectionMode="single"
          onSelectionChange={(keys) => {
            handleSelect(keys);
          }}
        >
          {results.map((item) => (
            <ListBox.Item key={item.url} id={item.url} textValue={item.url}>
              {searchType === "podcast" ? (
                <Image
                  src={item.icon_url}
                  alt={item.title}
                  className={cn(
                    "size-8 object-cover aspect-square rounded-[var(--ds-radius-small)] shrink-0",
                  )}
                />
              ) : (
                <FeedIcon feedId={null} url={item.url} />
              )}
              <div className="flex flex-col">
                <Label className="line-clamp-1">{item.title || item.url}</Label>
                <Description className="line-clamp-1">{item.title}</Description>
              </div>
              <ListBox.ItemIndicator />
            </ListBox.Item>
          ))}
        </ListBox>
      </ListboxWrapper>
    </>
  );
}
