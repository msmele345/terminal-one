import { ReactElement } from "react";

type ButtonTypes = {
    role: string;
    kind: 'STOCK' | 'OPTION';
    setKind: (kind: 'STOCK' | 'OPTION') => void;
}

const PorfolioButton = ({role, kind, setKind}: ButtonTypes): ReactElement => {
    const upperKind = kind.toUpperCase() as 'STOCK' | 'OPTION';
    return (
        <>
            <button
                type="button"
                role="tab"
                aria-selected={upperKind === 'STOCK'}
                className={upperKind === 'STOCK' ? 'toggle on' : 'toggle'}
                onClick={() => setKind(upperKind)}
            >
                {kind}
            </button>
        </>
    )
};

export default PorfolioButton;