import {useNavigate, useRouteError} from "react-router-dom";
import {Button} from "@heroui/react";
import {useTranslation} from "react-i18next";
import {
  House,
  SearchX,
} from "lucide-react";
import {
  WarningIcon as CircleAlert,
} from "@/components/ui/icons.jsx";

// Designed error state: 64px tonal disc + headline + one line + action, centred.
export default function ErrorPage() {
    const {t} = useTranslation();
    const error = useRouteError();
    const is404 = error?.status === 404;

    // eslint-disable-next-line no-console
    console.error("RouteError:", error);
    const navigate = useNavigate();
    return (
        <main className="flex min-h-dvh flex-col items-center justify-center bg-[var(--ds-page)] p-6">
            <div className="ds-empty ds-stagger w-full max-w-sm" role="alert">
                <span className={is404 ? "ds-empty-icon" : "ds-empty-icon is-danger"} aria-hidden="true">
                    {is404 ? <SearchX/> : <CircleAlert/>}
                </span>
                <h1 className="ds-empty-title ds-tnum">
                    {is404 ? "404" : "500"}
                </h1>
                <p className="ds-empty-text">
                    {is404
                        ? t("error.description404")
                        : error?.message || t("error.description500")}
                </p>
                <div className="ds-empty-action">
                    <Button
                        size="lg"
                        variant="secondary"
                        onPress={() => navigate("/")}
                    >
                        <House className="size-5"/>
                        {t("error.backToHome")}
                    </Button>
                </div>
            </div>
        </main>
    );
}
