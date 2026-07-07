class DomUtils {
    formatLabel(label: string): string {
        return label
            .toLowerCase()
            .split('_')
            .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
            .join(' ')
    }
};